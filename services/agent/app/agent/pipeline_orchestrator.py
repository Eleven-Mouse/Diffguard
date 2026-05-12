"""Pipeline orchestrator - composable multi-stage code review pipeline.

Supports automatic diff chunking: when a PR touches more than
``MAX_FILES_PER_CHUNK`` files, the diff is split into batches and each batch
is reviewed independently.  Issues from all chunks are merged and deduplicated
before the final false-positive filter pass.
"""

from __future__ import annotations

import logging
import time

from app.agent.llm_utils import create_llm
from app.agent.pipeline.stages.base import PipelineContext, PipelineStage, TokenUsageTracker
from app.agent.pipeline.stages.summary import SummaryStage
from app.agent.pipeline.stages.reviewer import ReviewerStage
from app.agent.pipeline.stages.aggregation import AggregationStage
from app.agent.pipeline.stages.false_positive_filter import FalsePositiveFilterStage
from app.models.schemas import (
    IssuePayload,
    ReviewRequest,
    ReviewResponse,
    ReviewStatus,
)
from app.tools.tool_client import JavaToolClient, create_tool_session, destroy_tool_session
from app.config import settings
from app.metrics import ReviewMetrics

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Chunking constants
# ---------------------------------------------------------------------------

MAX_FILES_PER_CHUNK = 10
MAX_CHARS_PER_CHUNK = 60_000  # ~15k tokens safety margin


# --- Default pipeline builder ---


def build_default_pipeline() -> list[PipelineStage]:
    """Build the standard 4-stage review pipeline."""
    return [
        SummaryStage(),
        ReviewerStage(),
        AggregationStage(),
        FalsePositiveFilterStage(),
    ]


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
    ) -> None:
        self.request = request
        self.stages = stages or build_default_pipeline()
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

            file_diffs = {e.file_path: e.content for e in req.diff_entries}

            context = PipelineContext(
                diff_text=diff_text,
                llm=llm_with_tracking,
                tool_client=tool_client,
                token_tracker=tracker,
                historical_context=self.historical_context,
                file_diffs=file_diffs,
            )

            for stage in stages:
                logger.info("Executing pipeline stage: %s", stage.name)
                t0 = time.monotonic()
                context = await stage.execute(context)
                metrics.record_stage(stage.name, (time.monotonic() - t0) * 1000)

            metrics.record_issues(context.final_issues)
            metrics.llm_total_tokens = tracker.total_tokens
            metrics.finish("completed")

            return ReviewResponse(
                request_id=req.request_id,
                status=ReviewStatus.COMPLETED,
                has_critical_flag=context.has_critical,
                issues=context.final_issues,
                summary=context.final_summary,
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
                chunk_file_diffs = {e.file_path: e.content for e in chunk_entries}
                context = PipelineContext(
                    diff_text=chunk_diff,
                    llm=llm_with_tracking,
                    tool_client=tool_client,
                    token_tracker=tracker,
                    historical_context=self.historical_context,
                    file_diffs=chunk_file_diffs,
                )

                try:
                    for stage in stages:
                        logger.info("[%s] stage: %s", chunk_label, stage.name)
                        t0 = time.monotonic()
                        context = await stage.execute(context)
                        metrics.record_stage(f"{stage.name}_chunk{idx+1}", (time.monotonic() - t0) * 1000)

                    all_issues.extend(context.final_issues)
                    if context.final_summary:
                        all_summaries.append(context.final_summary)
                    if context.has_critical:
                        has_critical = True

                except Exception as e:
                    logger.warning("[%s] failed: %s", chunk_label, e)

        finally:
            await self._maybe_destroy_tool_session(tool_client)

        # Deduplicate issues across chunks (same file + type + message)
        merged = _deduplicate_issues(all_issues)

        # Build a combined summary
        combined_summary = _merge_summaries(all_summaries, len(chunks))

        metrics.record_issues(merged)
        metrics.llm_total_tokens = tracker.total_tokens
        metrics.finish("completed")

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


# ---------------------------------------------------------------------------
# Chunking helpers
# ---------------------------------------------------------------------------


def _chunk_diff_entries(entries: list) -> list[list]:
    """Split diff entries into chunks that fit within token/char budgets.

    Each chunk respects both MAX_FILES_PER_CHUNK and MAX_CHARS_PER_CHUNK.
    """
    if len(entries) <= MAX_FILES_PER_CHUNK:
        return [entries]

    chunks: list[list] = []
    current: list = []
    current_chars = 0

    for entry in entries:
        entry_chars = len(entry.content)
        if current and (
            len(current) >= MAX_FILES_PER_CHUNK
            or current_chars + entry_chars > MAX_CHARS_PER_CHUNK
        ):
            chunks.append(current)
            current = []
            current_chars = 0

        current.append(entry)
        current_chars += entry_chars

    if current:
        chunks.append(current)

    logger.info(
        "Diff split into %d chunks (%d files total)",
        len(chunks), len(entries),
    )
    return chunks


def _deduplicate_issues(issues: list[IssuePayload]) -> list[IssuePayload]:
    """Deduplicate issues: same file + type + message → keep highest severity."""
    seen: dict[tuple, IssuePayload] = {}
    severity_rank = {"CRITICAL": 3, "WARNING": 2, "INFO": 1}

    for issue in issues:
        key = (issue.file, issue.type, issue.message[:120])
        if key in seen:
            existing = seen[key]
            if severity_rank.get(issue.severity.upper(), 0) > severity_rank.get(existing.severity.upper(), 0):
                seen[key] = issue
        else:
            seen[key] = issue

    return list(seen.values())


def _merge_summaries(summaries: list[str], total_chunks: int) -> str:
    """Build a combined summary from per-chunk summaries."""
    if not summaries:
        return f"Reviewed changes across {total_chunks} batches."
    if len(summaries) == 1:
        return summaries[0]
    return f"[{total_chunks}-batch review] " + " | ".join(summaries)
