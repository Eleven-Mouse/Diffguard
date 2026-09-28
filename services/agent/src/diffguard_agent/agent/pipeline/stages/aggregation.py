"""Aggregation stage - merges all review results into a final verdict."""

from __future__ import annotations

import json
import logging

from pydantic import BaseModel, Field

from diffguard_agent.agent.diff_parser import DiffLineMapper
from diffguard_agent.models.schemas import IssuePayload
from diffguard_agent.agent.pipeline.stages.base import PipelineContext, PipelineStage
from diffguard_agent.agent.llm_utils import load_prompt, invoke_with_retry

logger = logging.getLogger(__name__)

MAX_TOTAL_ISSUES = 50
MAX_CONTEXT_CHARS = 8000
CRITICAL_WEIGHT = 3
WARNING_WEIGHT = 2
INFO_WEIGHT = 1

_ROOT_PATTERNS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("sql-injection", ("sql注入", "sql injection", "sql拼接", "sql 拼接")),
    ("command-injection", ("命令注入", "command injection", "shell=true", "shell = true", "命令拼接")),
    ("hardcoded-secret", ("硬编码", "敏感凭据", "硬编码 token", "硬编码token", "hardcoded secret", "hardcoded credential")),
    ("secret-exposure", ("敏感信息泄露", "敏感数据泄露", "token 泄露", "token泄露", "secret exposure")),
    ("path-traversal", ("路径穿越", "path traversal")),
    ("null-dereference", ("空指针", "空值解引用", "null pointer", "null dereference")),
    ("resource-leak", ("资源泄漏", "资源未关闭", "文件句柄", "resource leak")),
    ("broad-exception", ("异常处理过于宽泛", "吞掉异常", "except exception", "broad exception")),
    ("complexity", ("嵌套过深", "圈复杂度", "复杂度过高", "nested if", "complexity")),
)

_INFO_PATTERNS = ("嵌套过深", "圈复杂度", "复杂度过高", "可读性", "语义不清", "健壮性", "脆弱实现")
_WARNING_PATTERNS = ("资源泄漏", "资源未关闭", "文件句柄", "吞掉异常", "异常处理过于宽泛", "空指针", "空值解引用", "缺少输入校验", "缺少参数校验")
_CRITICAL_PATTERNS = ("注入", "injection", "远程代码执行", "rce", "越权", "认证绕过", "权限提升", "数据丢失", "数据损坏", "资金", "硬编码密钥", "硬编码凭据", "hardcoded secret", "hardcoded credential", "敏感信息泄露", "敏感数据泄露", "token 泄露")


def _severity_weight(severity: str) -> int:
    s = severity.upper()
    if s == "CRITICAL":
        return CRITICAL_WEIGHT
    if s == "WARNING":
        return WARNING_WEIGHT
    return INFO_WEIGHT


def _text_for_matching(text: str) -> str:
    """Also match UTF-8 text that a compatible provider decoded as Latin-1."""
    if not text:
        return ""
    try:
        repaired = text.encode("latin-1").decode("utf-8")
    except (UnicodeDecodeError, UnicodeEncodeError):
        repaired = text
    return f"{text} {repaired}".lower()


def _issue_text(issue: IssuePayload) -> str:
    return " ".join(_text_for_matching(value) for value in (issue.type, issue.message, issue.suggestion))


def _issue_root(issue: IssuePayload) -> str | None:
    text = _issue_text(issue)
    for root, patterns in _ROOT_PATTERNS:
        if any(pattern in text for pattern in patterns):
            return root
    return None


def _calibrated_severity(issue: IssuePayload) -> str:
    """Apply one final, evidence-based severity scale after aggregation."""
    text = _issue_text(issue)
    if any(pattern in text for pattern in _CRITICAL_PATTERNS):
        return "CRITICAL"
    if any(pattern in text for pattern in _INFO_PATTERNS):
        return "INFO"
    if any(pattern in text for pattern in _WARNING_PATTERNS):
        return "WARNING"
    return issue.severity.upper() if issue.severity.upper() in {"CRITICAL", "WARNING", "INFO"} else "INFO"


def _same_root_issue(left: IssuePayload, right: IssuePayload) -> bool:
    if (left.file or "").strip() != (right.file or "").strip():
        return False
    root = _issue_root(left)
    if root is None or root != _issue_root(right):
        return False
    if left.line is None or right.line is None:
        return True
    return abs(left.line - right.line) <= 2


def _deduplicate_and_calibrate(issues: list[IssuePayload]) -> list[IssuePayload]:
    """Collapse nearby reports of the same root cause and normalize severity."""
    result: list[IssuePayload] = []
    for issue in issues:
        candidate = issue.model_copy(update={"severity": _calibrated_severity(issue)})
        duplicate_index = next(
            (index for index, existing in enumerate(result) if _same_root_issue(existing, candidate)),
            None,
        )
        if duplicate_index is None:
            result.append(candidate)
            continue

        existing = result[duplicate_index]
        existing_rank = _severity_weight(existing.severity)
        candidate_rank = _severity_weight(candidate.severity)
        if candidate_rank > existing_rank or (
            candidate_rank == existing_rank and candidate.confidence > existing.confidence
        ):
            result[duplicate_index] = candidate
    return result

def _build_reviewer_section(review_results: dict[str, str]) -> tuple[str, int]:
    """Build compact reviewer section with deduplication.

    Returns (reviewer_section_text, total_issues_count).
    """
    all_issues: list[tuple[int, str, dict]] = []

    for name, result_json in review_results.items():
        try:
            data = json.loads(result_json)
            for issue in data.get("issues", []):
                all_issues.append((_severity_weight(issue.get("severity", "INFO")), name, issue))
        except (json.JSONDecodeError, KeyError) as e:
            logger.warning("Failed to parse reviewer result '%s': %s", name, e)

    # Sort by severity desc, then keep top N
    all_issues.sort(key=lambda x: x[0], reverse=True)
    top_issues = all_issues[:MAX_TOTAL_ISSUES]

    # Group by reviewer (simple approach: keep original grouping by name)
    by_reviewer: dict[str, list[dict]] = {}
    for _, source_name, issue in top_issues:
        # Keep source reviewer when available; only fall back to heuristic for malformed source.
        reviewer = source_name or _guess_reviewer(issue)
        if reviewer not in by_reviewer:
            by_reviewer[reviewer] = []
        by_reviewer[reviewer].append(issue)

    total = len(all_issues)
    sections = []
    for name, result_json in review_results.items():
        try:
            data = json.loads(result_json)
            summary = data.get("summary", f"{name} 审查完成")
            issues = by_reviewer.get(name, [])[:15]
            compact = {"summary": summary, "issue_count": len(issues), "issues": issues}
            sections.append(f"\n【{name}】{json.dumps(compact, ensure_ascii=False)}\n")
        except (json.JSONDecodeError, KeyError):
            sections.append(f"\n【{name}】审查完成\n")

    reviewer_section = "".join(sections)
    if len(reviewer_section) > MAX_CONTEXT_CHARS:
        reviewer_section = reviewer_section[:MAX_CONTEXT_CHARS] + "\n...(truncated)"

    return reviewer_section, total


def _guess_reviewer(issue: dict) -> str:
    """Guess which reviewer produced an issue based on type/message."""
    t = issue.get("type", "").lower()
    msg = issue.get("message", "").lower()

    if any(k in t or k in msg for k in ["sql", "xss", "csrf", "injection", "auth", "secret", "hardcoded"]):
        return "security"
    if any(k in t or k in msg for k in ["null", "race", "resource", "leak", "overflow", "concurrency", "logic"]):
        return "logic"
    return "quality"


def _recover_reviewer_issues(review_results: dict[str, str]) -> list[IssuePayload]:
    """Keep reviewer findings when structured aggregation is unavailable."""
    recovered: list[IssuePayload] = []
    for name, result_json in review_results.items():
        try:
            data = json.loads(result_json)
            recovered.extend(IssuePayload.model_validate(issue) for issue in data.get("issues", []))
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as e:
            logger.warning("Failed to recover reviewer result '%s': %s", name, e)
    return recovered

class _AggregatedReview(BaseModel):
    has_critical: bool = False
    summary: str = ""
    issues: list[IssuePayload] = Field(default_factory=list)
    highlights: list[str] = Field(default_factory=list)
    test_suggestions: list[str] = Field(default_factory=list)


def _fallback_summary(total_llm_issues: int, pre_aggregated_count: int) -> str:
    total = total_llm_issues + pre_aggregated_count
    if total <= 0:
        return "审查完成，未发现需要报告的问题。"
    return f"审查完成，共识别 {total} 个问题。"


def _normalize_aggregated(
    raw: object,
    *,
    total_llm_issues: int,
    pre_aggregated_count: int,
) -> _AggregatedReview:
    """Normalize LLM aggregation output to a safe _AggregatedReview."""
    if isinstance(raw, _AggregatedReview):
        return raw

    if raw is None:
        logger.warning("Aggregation LLM returned None, using fallback result")
        return _AggregatedReview(
            summary=_fallback_summary(total_llm_issues, pre_aggregated_count),
        )

    # Backward compatibility for tests/mocks returning attribute objects
    if not isinstance(raw, dict) and hasattr(raw, "issues"):
        raw = {
            "has_critical": getattr(raw, "has_critical", False),
            "summary": getattr(raw, "summary", ""),
            "issues": getattr(raw, "issues", []),
            "highlights": getattr(raw, "highlights", []),
            "test_suggestions": getattr(raw, "test_suggestions", []),
        }

    try:
        normalized = _AggregatedReview.model_validate(raw)
        if not normalized.summary:
            normalized.summary = _fallback_summary(total_llm_issues, pre_aggregated_count)
        return normalized
    except Exception:
        logger.warning("Aggregation LLM returned invalid payload (%s), using fallback",
                       type(raw).__name__)
        return _AggregatedReview(
            summary=_fallback_summary(total_llm_issues, pre_aggregated_count),
        )


class AggregationStage(PipelineStage):
    """Merges reviewer outputs, deduplicates, maps line numbers, and produces final verdict."""

    @property
    def name(self) -> str:
        return "aggregation"

    async def execute(self, context: PipelineContext) -> PipelineContext:
        logger.info("Pipeline Stage [aggregation]: Merging %d reviewer results",
                    len(context.review.review_results))
        if context.input.llm is None:
            raise RuntimeError("LLM client is not initialized")

        # Build diff line mapper for accurate line number mapping
        line_mapper = DiffLineMapper(context.input.diff_text)

        # Collect pre-aggregated issues (e.g. from static_rules) — not JSON strings
        pre_aggregated: list[IssuePayload] = []
        json_results: dict[str, str] = {}
        for name, value in context.review.review_results.items():
            if isinstance(value, list):
                pre_aggregated.extend(value)
            else:
                json_results[name] = value

        system = load_prompt("pipeline/aggregation-system.txt")
        user_tpl = load_prompt("pipeline/aggregation-user.txt")

        reviewer_section, total_issues = _build_reviewer_section(json_results)
        logger.info("Aggregating ~%d total issues from %d reviewers + %d pre-aggregated",
                    total_issues, len(json_results), len(pre_aggregated))

        user = (user_tpl
                .replace("{{summary}}", context.summary.summary)
                .replace("{{reviewer_results}}", reviewer_section))

        structured_llm = context.input.llm.with_structured_output(_AggregatedReview)
        aggregated = await invoke_with_retry(
            structured_llm,
            [("system", system), ("human", user)]
        )
        aggregated = _normalize_aggregated(
            aggregated,
            total_llm_issues=total_issues,
            pre_aggregated_count=len(pre_aggregated),
        )
        if not aggregated.issues:
            recovered = _recover_reviewer_issues(json_results)
            if recovered:
                logger.warning("Aggregation produced no issues; preserving %d reviewer findings", len(recovered))
                aggregated.issues = recovered
                aggregated.has_critical = any(issue.severity.upper() == "CRITICAL" for issue in recovered)

        # Map diff-context line numbers → actual file line numbers for each issue
        final_issues: list[IssuePayload] = list(pre_aggregated)
        for issue in aggregated.issues:
            mapped = _map_issue_line_numbers(issue, line_mapper, context.input.diff_text)
            final_issues.append(mapped)

        final_issues = _deduplicate_and_calibrate(final_issues)
        context.aggregation.final_issues = final_issues
        context.aggregation.final_summary = aggregated.summary
        context.aggregation.has_critical = any(
            i.severity.upper() == "CRITICAL" for i in final_issues
        )
        context.aggregation.highlights = aggregated.highlights
        context.aggregation.test_suggestions = aggregated.test_suggestions

        logger.info(
            "Aggregation complete: %d issues (has_critical=%s)",
            len(final_issues), aggregated.has_critical,
        )
        return context


def _map_issue_line_numbers(issue: IssuePayload, line_mapper: DiffLineMapper, diff_text: str) -> IssuePayload:
    """Convert diff-context line number to actual file line number.

    The LLM reports 'line' in diff-context (1-based absolute offset).
    We convert it to the actual file line so GitHub comments land on the right line.
    """
    if issue.line is None or issue.file is None:
        return issue

    actual = line_mapper.diff_line_to_file_line(issue.file, issue.line)
    if actual is not None:
        issue.line = actual
        return issue

    # Fallback: try to find the nearest added line in the same file's hunk
    actual = _fallback_to_hunk_start(issue.file, issue.line, diff_text)
    if actual is not None:
        issue.line = actual
    return issue


def _fallback_to_hunk_start(file_path: str, diff_line: int, diff_text: str) -> int | None:
    """When exact mapping fails, find the nearest hunk's new_start as approximation."""
    import re

    current_file: str | None = None
    best_hunk_start: int | None = None
    best_hunk_diff_line: int | None = None

    for abs_line, raw_line in enumerate(diff_text.splitlines(), start=1):
        if raw_line.startswith("+++ "):
            path = raw_line[4:].split("\t")[0].rstrip()
            if path.startswith("a/"):
                path = path[2:]
            if path.startswith("b/"):
                path = path[2:]
            current_file = path
        if raw_line.startswith("@@ ") and current_file == file_path:
            m = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)?", raw_line)
            if m:
                hunk_start = int(m.group(1))
                # Track this hunk; keep it as candidate if the issue falls
                # within or near it.
                if best_hunk_diff_line is None or abs(abs_line - diff_line) < abs(best_hunk_diff_line - diff_line):
                    best_hunk_start = hunk_start
                    best_hunk_diff_line = abs_line

    return best_hunk_start
