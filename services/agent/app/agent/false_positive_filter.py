"""Two-stage false positive filter for code review findings.

Stage 1: HardExclusionRules – deterministic regex-based filtering (fast, cheap).
Stage 2: Optional LLM verification – per-finding AI analysis (expensive, opt-in).
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from typing import Any

from app.models.schemas import IssuePayload

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Hard exclusion rules
# ---------------------------------------------------------------------------

_DOS_PATTERNS: list[re.Pattern] = [
    re.compile(r"potential\s+denial\s+of\s+service", re.IGNORECASE),
    re.compile(r"dos\s+attack", re.IGNORECASE),
    re.compile(r"resource\s+exhaustion", re.IGNORECASE),
    re.compile(r"exhaust|overwhelm|overload\s+(?:resource|memory|cpu)", re.IGNORECASE),
    re.compile(r"unbounded\s+(?:growth|loop|recursion)", re.IGNORECASE),
]

_RATE_LIMIT_PATTERNS: list[re.Pattern] = [
    re.compile(r"consider\s+adding\s+rate\s+limit", re.IGNORECASE),
    re.compile(r"implement\s+rate\s+limit", re.IGNORECASE),
    re.compile(r"missing\s+rate\s+limit", re.IGNORECASE),
    re.compile(r"no\s+rate\s+limit", re.IGNORECASE),
]

_GENERIC_PERF_PATTERNS: list[re.Pattern] = [
    re.compile(r"consider\s+(?:adding|using|implementing)\s+(?:a\s+)?caching", re.IGNORECASE),
    re.compile(r"add\s+(?:a\s+)?caching\s+layer", re.IGNORECASE),
    re.compile(r"consider\s+(?:using|adding)\s+connection\s+pool", re.IGNORECASE),
    re.compile(r"use\s+(?:a\s+)?connection\s+pool", re.IGNORECASE),
]

_TEST_FILE_EXTENSIONS: tuple[str, ...] = (
    "Test.java", "Tests.java", "Spec.java", "IT.java",
    "_test.py", "test_", "Test.kt", "Tests.kt",
    ".test.ts", ".test.tsx", ".spec.ts", ".spec.tsx",
)

_DOC_FILE_EXTENSIONS: tuple[str, ...] = (
    ".md", ".txt", ".rst", ".adoc", ".tex", ".org",
)

_MEMORY_SAFETY_PATTERNS: list[re.Pattern] = [
    re.compile(r"buffer\s+overflow", re.IGNORECASE),
    re.compile(r"use[\s-]?after[\s-]?free", re.IGNORECASE),
    re.compile(r"double[\s-]?free", re.IGNORECASE),
    re.compile(r"stack\s+overflow", re.IGNORECASE),
    re.compile(r"heap\s+overflow", re.IGNORECASE),
    re.compile(r"out[\s-]?of[\s-]?bounds\s+(?:read|write|access)", re.IGNORECASE),
    re.compile(r"integer\s+overflow", re.IGNORECASE),
    re.compile(r"memory\s+(?:corruption|safety)", re.IGNORECASE),
]

_C_CPP_EXTENSIONS: frozenset[str] = frozenset({".c", ".cc", ".cpp", ".h", ".hpp"})

_GENERIC_SUGGESTION_PATTERNS: list[re.Pattern] = [
    re.compile(r"follow\s+best\s+practices?", re.IGNORECASE),
    re.compile(r"improve\s+code\s+quality", re.IGNORECASE),
    re.compile(r"consider\s+refactoring", re.IGNORECASE),
    re.compile(r"follow\s+(?:coding|code)\s+standards?", re.IGNORECASE),
    re.compile(r"this\s+(?:code|method|function)\s+could\s+be\s+improved", re.IGNORECASE),
    re.compile(r"add\s+(?:more\s+)?(?:comments|documentation)", re.IGNORECASE),
]


def _file_extension(file_path: str) -> str:
    idx = file_path.rfind(".")
    return file_path[idx:].lower() if idx >= 0 else ""


class HardExclusionRules:
    """Deterministic regex-based false positive pre-filter."""

    @staticmethod
    def check(issue: IssuePayload) -> str | None:
        file_path = issue.file or ""
        ext = _file_extension(file_path)
        text = f"{issue.type} {issue.message} {issue.suggestion}".lower()

        if ext in _DOC_FILE_EXTENSIONS or file_path.endswith(_DOC_FILE_EXTENSIONS):
            return "Finding in documentation file"
        if any(file_path.endswith(suffix) or (suffix in file_path and suffix.startswith("test_"))
               for suffix in _TEST_FILE_EXTENSIONS):
            return "Finding in test file"
        if any(p.search(text) for p in _DOS_PATTERNS):
            return "Generic DOS/resource exhaustion finding"
        if any(p.search(text) for p in _RATE_LIMIT_PATTERNS):
            return "Generic rate limiting suggestion"
        if any(p.search(text) for p in _GENERIC_PERF_PATTERNS):
            return "Generic performance suggestion without specifics"
        if ext not in _C_CPP_EXTENSIONS:
            if any(p.search(text) for p in _MEMORY_SAFETY_PATTERNS):
                return "Memory safety finding in non-C/C++ code"
        if any(p.search(text) for p in _GENERIC_SUGGESTION_PATTERNS):
            return "Overly generic suggestion without actionable specifics"
        return None


# ---------------------------------------------------------------------------
# Precedents & prompt templates
# ---------------------------------------------------------------------------

_FALSE_POSITIVE_PRECEDENTS: list[dict[str, str]] = [
    {"pattern": "JPA @Query with :named parameters", "verdict": "not_sql_injection",
     "reason": "JPA @Query with :param uses parameterized queries internally"},
    {"pattern": "Spring @Value with ${...} placeholder", "verdict": "not_hardcoded_secret",
     "reason": "Spring property placeholder references external config"},
    {"pattern": "PreparedStatement with parameter binding", "verdict": "not_sql_injection",
     "reason": "PreparedStatement uses parameterized queries"},
    {"pattern": "MyBatis #{param} syntax", "verdict": "not_sql_injection",
     "reason": "MyBatis #{} uses parameterized binding; only ${} is unsafe"},
    {"pattern": "Spring Security @PreAuthorize / @Secured annotation", "verdict": "not_missing_auth",
     "reason": "Method-level security annotations provide authorization"},
    {"pattern": "Log statement with structured logging", "verdict": "not_log_injection",
     "reason": "Structured logging frameworks handle sanitization"},
    {"pattern": "UUID usage as identifier", "verdict": "not_predictable_id",
     "reason": "UUIDs are unguessable"},
    {"pattern": "Environment variable or config property reference", "verdict": "not_hardcoded",
     "reason": "Environment variables and config properties are trusted input"},
    {"pattern": "try-with-resources in Java", "verdict": "not_resource_leak",
     "reason": "try-with-resources guarantees resource cleanup"},
    {"pattern": "Spring @Transactional on service method", "verdict": "not_missing_transaction",
     "reason": "Annotation-based transaction management is active"},
]

_LLM_VERIFICATION_SYSTEM = (
    "You are a senior code reviewer evaluating whether a specific finding "
    "from an automated code review is a genuine issue or a false positive.\n"
    "Respond ONLY with valid JSON: {\"is_real\": bool, \"confidence\": float(0-1), "
    "\"reasoning\": string}\n"
    "No markdown, no code blocks, no extra text."
)

_LLM_VERIFICATION_USER = """\
## Precedents (known false positive patterns for Java/Spring):
{precedents}

## Finding to evaluate:
- Severity: {severity}
- File: {file}
- Line: {line}
- Type: {type}
- Message: {message}
- Suggestion: {suggestion}

## Code diff context:
{diff_context}

Is this finding a genuine issue that should be reported to the developer?
"""


@dataclass
class FilterStats:
    total_input: int = 0
    excluded_by_hard_rules: int = 0
    confidence_adjusted: int = 0
    passed_through: int = 0
    exclusion_reasons: dict[str, int] = field(default_factory=dict)


class FindingsFilter:
    """Two-stage false positive filter: hard rules -> optional LLM verification."""

    def __init__(
        self,
        confidence_threshold: float = 0.5,
        enable_llm_verification: bool = False,
        llm: Any = None,
        diff_context: str = "",
        custom_precedents: list[dict[str, str]] | None = None,
    ) -> None:
        self._threshold = confidence_threshold
        self._verify = enable_llm_verification
        self._llm = llm
        self._diff_context = diff_context
        self._precedents = list(_FALSE_POSITIVE_PRECEDENTS)
        if custom_precedents:
            self._precedents.extend(custom_precedents)

    async def filter_issues(
        self, issues: list[IssuePayload]
    ) -> tuple[list[IssuePayload], FilterStats]:
        stats = FilterStats(total_input=len(issues))
        try:
            return await self._do_filter(issues, stats)
        except Exception:
            logger.warning("False positive filter failed, keeping all issues", exc_info=True)
            stats.passed_through = len(issues)
            return issues, stats

    async def _do_filter(
        self, issues: list[IssuePayload], stats: FilterStats
    ) -> tuple[list[IssuePayload], FilterStats]:
        hard_rules = HardExclusionRules()

        for issue in issues:
            reason = hard_rules.check(issue)
            if reason is not None:
                stats.excluded_by_hard_rules += 1
                stats.exclusion_reasons[reason] = stats.exclusion_reasons.get(reason, 0) + 1
                issue.confidence = 0.0
                issue.filter_metadata = {"excluded": True, "reason": reason, "stage": "hard_rules"}

        if self._verify and self._llm is not None:
            issues = await self._llm_verify_stage(issues, stats)

        stats.passed_through = sum(
            1 for i in issues if not i.filter_metadata.get("excluded", False)
        )
        return issues, stats

    async def _llm_verify_stage(
        self, issues: list[IssuePayload], stats: FilterStats
    ) -> list[IssuePayload]:
        precedents_text = "\n".join(
            f"- {p['pattern']}: {p['reason']}" for p in self._precedents
        )

        for issue in issues:
            if issue.filter_metadata.get("excluded", False):
                continue

            try:
                prompt = _LLM_VERIFICATION_USER.format(
                    precedents=precedents_text,
                    severity=issue.severity,
                    file=issue.file,
                    line=issue.line or 0,
                    type=issue.type,
                    message=issue.message,
                    suggestion=issue.suggestion,
                    diff_context=self._diff_context[:2000],
                )

                from langchain_core.messages import HumanMessage, SystemMessage

                response = await self._llm.ainvoke([
                    SystemMessage(content=_LLM_VERIFICATION_SYSTEM),
                    HumanMessage(content=prompt),
                ])

                text = response.content if hasattr(response, "content") else str(response)
                parsed = json.loads(text.strip().removeprefix("```json").removesuffix("```").strip())

                is_real = parsed.get("is_real", True)
                llm_confidence = float(parsed.get("confidence", 0.8))

                if not is_real:
                    reason = parsed.get("reasoning", "LLM classified as false positive")
                    stats.exclusion_reasons["LLM: " + reason[:80]] = (
                        stats.exclusion_reasons.get("LLM: " + reason[:80], 0) + 1
                    )
                    issue.confidence = 0.0
                    issue.filter_metadata = {
                        "excluded": True,
                        "reason": reason,
                        "stage": "llm_verification",
                    }
                else:
                    issue.confidence = llm_confidence
                    if llm_confidence < self._threshold:
                        stats.confidence_adjusted += 1
                        issue.filter_metadata = {
                            "excluded": True,
                            "reason": f"Below confidence threshold ({llm_confidence:.2f} < {self._threshold})",
                            "stage": "confidence_threshold",
                        }

            except Exception:
                logger.warning("LLM verification failed for issue, keeping it: %s", issue.message[:80])

        return issues
