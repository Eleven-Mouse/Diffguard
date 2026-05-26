"""Pipeline orchestrator - composable multi-stage code review pipeline.

Supports automatic diff chunking: when a PR touches more than
``MAX_FILES_PER_CHUNK`` files, the diff is split into batches and each batch
is reviewed independently.  Issues from all chunks are merged and deduplicated
before the final false-positive filter pass.
"""

from __future__ import annotations

import logging
import time
import re
from dataclasses import dataclass

from diffguard_agent.agent.llm_utils import create_llm
from diffguard_agent.agent.pipeline.stages.base import (
    PipelineContext,
    PipelineInput,
    PipelineStage,
    TokenUsageTracker,
)
from diffguard_agent.agent.pipeline.stages.summary import SummaryStage
from diffguard_agent.agent.pipeline.stages.reviewer import ReviewerStage
from diffguard_agent.agent.pipeline.stages.aggregation import AggregationStage
from diffguard_agent.agent.pipeline.stages.fp_filter_stage import FalsePositiveFilterStage
from diffguard_agent.models.schemas import (
    IssuePayload,
    ReviewRequest,
    ReviewResponse,
    ReviewStatus,
)
from diffguard_agent.tools.tool_client import JavaToolClient, create_tool_session, destroy_tool_session
from diffguard_agent.config import settings
from diffguard_agent.metrics import ReviewMetrics

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Chunking constants
# ---------------------------------------------------------------------------

MAX_FILES_PER_CHUNK = 10
MAX_CHARS_PER_CHUNK = 60_000  # ~15k tokens safety margin
MAX_TOKENS_PER_CHUNK = 12_000
SOFT_TOKENS_PER_CHUNK = 9_000
AVG_CHARS_PER_TOKEN = 4
MAX_FAILED_CHUNK_RATIO = 0.5


# --- Default pipeline builder ---


def build_default_pipeline(enable_fp_filter: bool = True) -> list[PipelineStage]:
    """Build the standard 4-stage review pipeline."""
    stages: list[PipelineStage] = [
        SummaryStage(),
        ReviewerStage(),
        AggregationStage(),
    ]
    if enable_fp_filter:
        stages.append(FalsePositiveFilterStage())
    return stages


# --- Orchestrator ---


class PipelineOrchestrator:
    """Composable multi-stage pipeline orchestrator.

    Args:
        request: The review request.
        stages: Optional list of PipelineStage instances. Defaults to the
            standard summary -> review -> aggregation pipeline.
    """

    def __init__(
        self,
        request: ReviewRequest,
        stages: list[PipelineStage] | None = None,
        historical_context: str = "",
        enable_fp_filter: bool = True,
    ) -> None:
        self.request = request
        self.stages = stages or build_default_pipeline(enable_fp_filter=enable_fp_filter)
        self.historical_context = historical_context

    async def run(self) -> ReviewResponse:
        req = self.request
        llm = create_llm(req)

        # Decide whether to chunk
        chunks = _chunk_diff_entries(req.diff_entries)

        if len(chunks) <= 1:
            return await self._run_single(llm, req, self.stages)

        # Multi-chunk mode: run pipeline per chunk, then merge
        return await self._run_chunked(llm, req, self.stages, chunks)

    # ------------------------------------------------------------------
    # Single-run (no chunking)
    # ------------------------------------------------------------------

    async def _run_single(
        self,
        llm,
        req: ReviewRequest,
        stages: list[PipelineStage],
    ) -> ReviewResponse:
        diff_text = "\n".join(e.content for e in req.diff_entries)
        tool_client = None
        tracker = TokenUsageTracker()
        metrics = ReviewMetrics(request_id=req.request_id)

        try:
            tool_client = await self._maybe_create_tool_session(req)

            # Attach tracker so all LLM calls report token usage
            llm_with_tracking = llm.with_config(callbacks=[tracker])

            file_diffs = _build_file_diff_map(req.diff_entries)

            context = PipelineContext(
                input=PipelineInput(
                    diff_text=diff_text,
                    llm=llm_with_tracking,
                    tool_client=tool_client,
                    token_tracker=tracker,
                    historical_context=self.historical_context,
                    file_diffs=file_diffs,
                ),
            )

            for stage in stages:
                logger.info("Executing pipeline stage: %s", stage.name)
                t0 = time.monotonic()
                context = await stage.execute(context)
                metrics.record_stage(stage.name, (time.monotonic() - t0) * 1000)

            metrics.record_issues(context.aggregation.final_issues)
            metrics.llm_total_tokens = tracker.total_tokens
            metrics.finish("completed")

            return ReviewResponse(
                request_id=req.request_id,
                status=ReviewStatus.COMPLETED,
                has_critical_flag=context.aggregation.has_critical,
                issues=context.aggregation.final_issues,
                summary=context.aggregation.final_summary,
                total_tokens_used=tracker.total_tokens,
            )
        except Exception as e:
            logger.exception("Pipeline failed")
            metrics.llm_total_tokens = tracker.total_tokens
            metrics.finish("failed")
            return ReviewResponse(
                request_id=req.request_id,
                status=ReviewStatus.FAILED,
                error=str(e),
                total_tokens_used=tracker.total_tokens,
            )
        finally:
            await self._maybe_destroy_tool_session(tool_client)

    # ------------------------------------------------------------------
    # Chunked run
    # ------------------------------------------------------------------

    async def _run_chunked(
        self,
        llm,
        req: ReviewRequest,
        stages: list[PipelineStage],
        chunks: list[list[object]],
    ) -> ReviewResponse:
        """Run the pipeline for each chunk and merge results."""
        all_issues: list[IssuePayload] = []
        all_summaries: list[str] = []
        has_critical = False
        failed_chunks: list[str] = []
        fallback_chunks = 0
        tracker = TokenUsageTracker()
        metrics = ReviewMetrics(request_id=req.request_id, chunk_count=len(chunks))

        # Create a shared tool session for all chunks when available
        tool_client = None
        try:
            tool_client = await self._maybe_create_tool_session(req)

            # Attach tracker so all LLM calls report token usage
            llm_with_tracking = llm.with_config(callbacks=[tracker])

            for idx, chunk_entries in enumerate(chunks):
                chunk_label = f"chunk {idx + 1}/{len(chunks)}"
                logger.info("Processing %s (%d files)", chunk_label, len(chunk_entries))

                chunk_diff = "\n".join(e.content for e in chunk_entries)
                chunk_file_diffs = _build_file_diff_map(chunk_entries)

                try:
                    context = await self._run_chunk_pipeline(
                        chunk_diff=chunk_diff,
                        chunk_file_diffs=chunk_file_diffs,
                        llm=llm_with_tracking,
                        tool_client=tool_client,
                        tracker=tracker,
                        stages=stages,
                        chunk_index=idx + 1,
                        metrics=metrics,
                    )

                    all_issues.extend(context.aggregation.final_issues)
                    if context.aggregation.final_summary:
                        all_summaries.append(context.aggregation.final_summary)
                    if context.aggregation.has_critical:
                        has_critical = True

                except Exception as e:
                    if _is_prompt_too_long_error(e):
                        logger.warning("[%s] prompt too long, retrying with compact context", chunk_label)
                        compact_diff = _build_compact_chunk_diff(chunk_entries)
                        try:
                            context = await self._run_chunk_pipeline(
                                chunk_diff=compact_diff,
                                chunk_file_diffs=chunk_file_diffs,
                                llm=llm_with_tracking,
                                tool_client=tool_client,
                                tracker=tracker,
                                stages=stages,
                                chunk_index=idx + 1,
                                metrics=metrics,
                                stage_name_suffix="_fallback",
                            )
                            fallback_chunks += 1
                            all_issues.extend(context.aggregation.final_issues)
                            if context.aggregation.final_summary:
                                all_summaries.append(context.aggregation.final_summary)
                            if context.aggregation.has_critical:
                                has_critical = True
                            continue
                        except Exception as fallback_error:
                            e = fallback_error

                    failure = f"{chunk_label}: {e}"
                    failed_chunks.append(failure)
                    logger.warning("[%s] failed: %s", chunk_label, e)

        finally:
            await self._maybe_destroy_tool_session(tool_client)

        # If every chunk failed, surface a hard failure instead of returning an
        # empty successful review.
        if failed_chunks and len(failed_chunks) == len(chunks):
            err = f"All {len(chunks)} chunks failed. First error: {failed_chunks[0]}"
            metrics.llm_total_tokens = tracker.total_tokens
            metrics.finish("failed")
            return ReviewResponse(
                request_id=req.request_id,
                status=ReviewStatus.FAILED,
                error=err,
                total_tokens_used=tracker.total_tokens,
            )

        # If failure ratio is too high, fail the whole review.
        failed_ratio = len(failed_chunks) / len(chunks) if chunks else 0.0
        fail_ratio_threshold = _max_failed_chunk_ratio()
        if failed_chunks and failed_ratio > fail_ratio_threshold:
            err = (
                f"Too many chunk failures ({len(failed_chunks)}/{len(chunks)}; "
                f"ratio={failed_ratio:.2f}, threshold={fail_ratio_threshold:.2f}). "
                f"First error: {failed_chunks[0]}"
            )
            metrics.llm_total_tokens = tracker.total_tokens
            metrics.finish("failed")
            return ReviewResponse(
                request_id=req.request_id,
                status=ReviewStatus.FAILED,
                error=err,
                total_tokens_used=tracker.total_tokens,
            )

        # Deduplicate issues across chunks (same file + type + message)
        merged = _deduplicate_issues(all_issues)

        # Build a combined summary
        combined_summary = _merge_summaries(all_summaries, len(chunks))
        if fallback_chunks:
            combined_summary = (
                f"[fallback-applied] compact context retry used on {fallback_chunks} chunk(s). "
                f"{combined_summary}"
            )
        if failed_chunks:
            combined_summary = (
                f"[partial review] {len(failed_chunks)}/{len(chunks)} chunks failed. "
                f"{combined_summary}"
            )

        metrics.record_issues(merged)
        metrics.llm_total_tokens = tracker.total_tokens
        metrics.finish("completed_partial" if failed_chunks else "completed")

        return ReviewResponse(
            request_id=req.request_id,
            status=ReviewStatus.COMPLETED,
            has_critical_flag=has_critical,
            issues=merged,
            summary=combined_summary,
            total_tokens_used=tracker.total_tokens,
        )

    # ------------------------------------------------------------------
    # Tool session helpers
    # ------------------------------------------------------------------

    async def _maybe_create_tool_session(self, req: ReviewRequest):
        if not req.tool_server_url:
            return None
        return await create_tool_session(
            req.tool_server_url,
            req.diff_entries,
            req.project_dir,
            req.allowed_files,
            tool_secret=settings.DIFFGUARD_TOOL_SECRET,
        )

    @staticmethod
    async def _maybe_destroy_tool_session(client) -> None:
        if client is None:
            return
        try:
            await destroy_tool_session(client)
        except Exception:
            pass

    async def _run_chunk_pipeline(
        self,
        *,
        chunk_diff: str,
        chunk_file_diffs: dict[str, str],
        llm,
        tool_client,
        tracker: TokenUsageTracker,
        stages: list[PipelineStage],
        chunk_index: int,
        metrics: ReviewMetrics | None = None,
        stage_name_suffix: str = "",
    ) -> PipelineContext:
        context = PipelineContext(
            input=PipelineInput(
                diff_text=chunk_diff,
                llm=llm,
                tool_client=tool_client,
                token_tracker=tracker,
                historical_context=self.historical_context,
                file_diffs=chunk_file_diffs,
            ),
        )

        for stage in stages:
            logger.info("[chunk %d%s] stage: %s", chunk_index, stage_name_suffix, stage.name)
            t0 = time.monotonic()
            context = await stage.execute(context)
            if metrics is not None:
                metrics.record_stage(
                    f"{stage.name}_chunk{chunk_index}{stage_name_suffix}",
                    (time.monotonic() - t0) * 1000,
                )
        return context


# ---------------------------------------------------------------------------
# Chunking helpers
# ---------------------------------------------------------------------------


def _chunk_diff_entries(entries: list) -> list[list]:
    """Split entries with token-aware budgets and oversized-file hunk splitting."""
    if not entries:
        return [[]]

    prepared: list = []
    total_chars = 0
    total_tokens = 0
    for entry in entries:
        split_entries = _split_oversized_entry(entry)
        prepared.extend(split_entries)
        for e in split_entries:
            total_chars += len(e.content)
            total_tokens += _estimate_entry_tokens(e)

    # Fast path: only one chunk needed.
    max_files = _max_files_per_chunk()
    max_chars = _max_chars_per_chunk()
    max_tokens = _max_tokens_per_chunk()
    if len(prepared) <= max_files and total_chars <= max_chars and total_tokens <= max_tokens:
        return [prepared]

    chunks = _pack_entries_into_chunks(prepared)
    logger.info(
        "Diff split into %d chunks (%d original files, %d packed entries, ~%d tokens)",
        len(chunks), len(entries), len(prepared), total_tokens,
    )
    return chunks


@dataclass
class _ChunkBudget:
    entries: list
    chars: int = 0
    tokens: int = 0

    @property
    def file_count(self) -> int:
        return len(self.entries)


def _estimate_entry_tokens(entry) -> int:
    if getattr(entry, "token_count", 0) and entry.token_count > 0:
        return int(entry.token_count)
    return max(1, len(entry.content) // AVG_CHARS_PER_TOKEN)


def _pack_entries_into_chunks(entries: list) -> list[list]:
    """First-fit decreasing packing with hard limits and soft token target."""
    max_files = _max_files_per_chunk()
    max_chars = _max_chars_per_chunk()
    max_tokens = _max_tokens_per_chunk()
    soft_tokens = _soft_tokens_per_chunk(max_tokens)

    items = sorted(
        entries,
        key=lambda e: max(len(e.content), _estimate_entry_tokens(e) * AVG_CHARS_PER_TOKEN),
        reverse=True,
    )
    budgets: list[_ChunkBudget] = []

    for entry in items:
        entry_chars = len(entry.content)
        entry_tokens = _estimate_entry_tokens(entry)
        placed = False

        # Pass 1: prefer chunks still under soft target.
        ordered = sorted(budgets, key=lambda b: b.tokens)
        for chunk in ordered:
            if chunk.tokens >= soft_tokens:
                continue
            if (
                chunk.file_count < max_files
                and chunk.chars + entry_chars <= max_chars
                and chunk.tokens + entry_tokens <= max_tokens
            ):
                chunk.entries.append(entry)
                chunk.chars += entry_chars
                chunk.tokens += entry_tokens
                placed = True
                break

        # Pass 2: any chunk that fits hard limits.
        if not placed:
            for chunk in ordered:
                if (
                    chunk.file_count < max_files
                    and chunk.chars + entry_chars <= max_chars
                    and chunk.tokens + entry_tokens <= max_tokens
                ):
                    chunk.entries.append(entry)
                    chunk.chars += entry_chars
                    chunk.tokens += entry_tokens
                    placed = True
                    break

        if not placed:
            budgets.append(_ChunkBudget(entries=[entry], chars=entry_chars, tokens=entry_tokens))

    return [b.entries for b in budgets]


def _split_oversized_entry(entry) -> list:
    """Split a huge single-file diff by hunks to keep hard limits."""
    max_chars = _max_chars_per_chunk()
    max_tokens = _max_tokens_per_chunk()
    if (
        len(entry.content) <= max_chars
        and _estimate_entry_tokens(entry) <= max_tokens
    ):
        return [entry]

    hunks = _extract_diff_hunks(entry.content)
    if not hunks:
        return [entry]

    split_entries: list = []
    current_hunks: list[str] = []
    current_chars = len(hunks["header"])

    for hunk in hunks["hunks"]:
        if len(hunk) > max_chars:
            # Rare case: single giant hunk. Split by lines while preserving
            # one hunk header per segment.
            for part in _split_large_hunk(hunk, max_chars):
                if current_hunks and current_chars + len(part) > max_chars:
                    split_entries.append(
                        _clone_entry(entry, hunks["header"] + "".join(current_hunks))
                    )
                    current_hunks = []
                    current_chars = len(hunks["header"])
                current_hunks.append(part)
                current_chars += len(part)
            continue

        hunk_chars = len(hunk)
        if current_hunks and current_chars + hunk_chars > max_chars:
            split_entries.append(
                _clone_entry(entry, hunks["header"] + "".join(current_hunks))
            )
            current_hunks = []
            current_chars = len(hunks["header"])

        current_hunks.append(hunk)
        current_chars += hunk_chars

    if current_hunks:
        split_entries.append(_clone_entry(entry, hunks["header"] + "".join(current_hunks)))

    return split_entries if split_entries else [entry]


def _split_large_hunk(hunk: str, max_chars: int) -> list[str]:
    """Split a very large hunk into smaller pseudo-hunks."""
    lines = hunk.splitlines(keepends=True)
    if not lines:
        return [hunk]
    header = lines[0]
    body = lines[1:]
    pieces: list[str] = []
    cur: list[str] = [header]
    cur_chars = len(header)

    for line in body:
        if cur_chars + len(line) > max_chars and len(cur) > 1:
            pieces.append("".join(cur))
            cur = [header, line]
            cur_chars = len(header) + len(line)
        else:
            cur.append(line)
            cur_chars += len(line)

    if cur:
        pieces.append("".join(cur))
    return pieces


def _extract_diff_hunks(content: str) -> dict | None:
    """Parse one-file unified diff into header + hunk blocks."""
    lines = content.splitlines(keepends=True)
    if not lines:
        return None

    header: list[str] = []
    hunks: list[str] = []
    current_hunk: list[str] = []
    seen_hunk = False

    for line in lines:
        if line.startswith("@@ "):
            seen_hunk = True
            if current_hunk:
                hunks.append("".join(current_hunk))
            current_hunk = [line]
            continue

        if not seen_hunk:
            header.append(line)
        else:
            current_hunk.append(line)

    if current_hunk:
        hunks.append("".join(current_hunk))

    if not hunks:
        return None
    return {"header": "".join(header), "hunks": hunks}


def _clone_entry(entry, content: str):
    """Clone a diff entry while preserving compatible schema fields."""
    data = entry.model_dump() if hasattr(entry, "model_dump") else {
        "file_path": entry.file_path,
        "content": entry.content,
        "token_count": getattr(entry, "token_count", 0),
    }
    data["content"] = content
    data["token_count"] = max(1, len(content) // AVG_CHARS_PER_TOKEN)
    return entry.__class__(**data)


def _build_file_diff_map(entries: list) -> dict[str, str]:
    """Build file->diff map, concatenating split segments of the same file."""
    out: dict[str, list[str]] = {}
    for e in entries:
        out.setdefault(e.file_path, []).append(e.content)
    return {k: "\n".join(v) for k, v in out.items()}


def _build_compact_chunk_diff(entries: list) -> str:
    """Build compact chunk context for prompt-too-long fallback retries."""
    files = [e.file_path for e in entries]
    lines = [
        "[DIFF_OMITTED_DUE_TO_SIZE]",
        "Raw diff omitted for this chunk because prompt exceeded model limits.",
        "Use available tools to inspect changed files and infer security/logic risks.",
        "Changed files:",
    ]
    lines.extend(f"- {fp}" for fp in files)
    return "\n".join(lines)


def _is_prompt_too_long_error(exc: Exception) -> bool:
    msg = str(exc).lower()
    patterns = (
        "prompt is too long",
        "context length",
        "maximum context length",
        "too many tokens",
        "token limit",
        "input too long",
        "request too large",
        "context window",
    )
    return any(p in msg for p in patterns)


def _max_files_per_chunk() -> int:
    return max(1, int(getattr(settings, "CHUNK_MAX_FILES", MAX_FILES_PER_CHUNK)))


def _max_chars_per_chunk() -> int:
    return max(1_000, int(getattr(settings, "CHUNK_MAX_CHARS", MAX_CHARS_PER_CHUNK)))


def _max_tokens_per_chunk() -> int:
    return max(100, int(getattr(settings, "CHUNK_MAX_TOKENS", MAX_TOKENS_PER_CHUNK)))


def _soft_tokens_per_chunk(max_tokens: int) -> int:
    configured = int(getattr(settings, "CHUNK_SOFT_TOKENS", SOFT_TOKENS_PER_CHUNK))
    if configured <= 0:
        return max(1, int(max_tokens * 0.75))
    return min(configured, max_tokens)


def _max_failed_chunk_ratio() -> float:
    raw = float(getattr(settings, "CHUNK_MAX_FAILED_RATIO", MAX_FAILED_CHUNK_RATIO))
    if raw < 0:
        return 0.0
    if raw > 1:
        return 1.0
    return raw


def _deduplicate_issues(issues: list[IssuePayload]) -> list[IssuePayload]:
    """Deduplicate issues with a stable fingerprint and keep highest severity."""
    seen: dict[tuple, IssuePayload] = {}
    severity_rank = {"CRITICAL": 3, "WARNING": 2, "INFO": 1}

    for issue in issues:
        key = _issue_dedup_key(issue)
        if key in seen:
            existing = seen[key]
            if severity_rank.get(issue.severity.upper(), 0) > severity_rank.get(existing.severity.upper(), 0):
                seen[key] = issue
        else:
            seen[key] = issue

    return list(seen.values())


def _normalize_text_for_key(text: str) -> str:
    """Normalize text so trivial formatting differences don't break dedup."""
    if not text:
        return ""
    normalized = re.sub(r"\s+", " ", text).strip().lower()
    # Keep enough context to distinguish similar findings.
    return normalized[:240]


def _issue_dedup_key(issue: IssuePayload) -> tuple:
    """Build a dedup key with stronger precision than message prefix only.

    Priority:
      1) file + type + line + normalized message
      2) if line missing, include suggestion as extra discriminator
    """
    file_key = (issue.file or "").strip()
    type_key = (issue.type or "").strip().lower()
    msg_key = _normalize_text_for_key(issue.message or "")
    line_key = issue.line if issue.line is not None else -1

    if line_key >= 0:
        return (file_key, type_key, line_key, msg_key)

    suggestion_key = _normalize_text_for_key(issue.suggestion or "")
    return (file_key, type_key, msg_key, suggestion_key)


def _merge_summaries(summaries: list[str], total_chunks: int) -> str:
    """Build a combined summary from per-chunk summaries."""
    if not summaries:
        return f"Reviewed changes across {total_chunks} batches."
    if len(summaries) == 1:
        return summaries[0]
    return f"[{total_chunks}-batch review] " + " | ".join(summaries)
