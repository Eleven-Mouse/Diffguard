"""Static rules stage - zero LLM cost pre-review check.

Scans diff content against built-in pattern rules (SQL injection,
hardcoded secrets, dangerous functions, complexity) before any LLM
call. Issues found here are appended to the pipeline context so they
reach the final aggregation stage.
"""

from __future__ import annotations

import logging
import re

from app.agent.pipeline.stages.base import PipelineContext, PipelineStage
from app.models.schemas import IssuePayload

logger = logging.getLogger(__name__)

# --- Static rule patterns ---

_SQL_INJECTION = re.compile(
    r"(String\.format.*SELECT|String\.format.*INSERT|String\.format.*UPDATE|"
    r"String\.format.*DELETE|executeQuery\s*\(\s*\"|"
    r"statement\.execute\s*\(\s*\"|"
    r"\+\s*\"?\s*(SELECT|INSERT|UPDATE|DELETE)\s)",
    re.I,
)

_HARDCODED_SECRET = re.compile(
    r"(password\s*=\s*\"[^\"]+\"|api_?key\s*=\s*\"[^\"]+\"|"
    r"secret\s*=\s*\"[^\"]+\"|token\s*=\s*\"[^\"]{16,}\")",
    re.I,
)

_DANGEROUS_FUNCTIONS = re.compile(
    r"(Runtime\.getRuntime\(\)|ProcessBuilder|Thread\.sleep\s*\(\s*\d{5,}|"
    r"System\.exit\s*\(|eval\s*\(|exec\s*\(|subprocess\.call|os\.system)",
    re.I,
)

_COMPLEXITY_SIGNALS = re.compile(
    r"(if\s*\(.*&&.*&&.*&&|if\s*\(.*\|\|.*\|\|.*\|\||"
    r"for\s*\(.*for\s*\(.*for\s*\()",
    re.I,
)

_RULES = [
    (_SQL_INJECTION, "sql_injection", "WARNING", "Potential SQL injection: string concatenation in SQL query"),
    (_HARDCODED_SECRET, "hardcoded_secret", "CRITICAL", "Hardcoded secret detected: credentials should use environment variables"),
    (_DANGEROUS_FUNCTIONS, "dangerous_function", "WARNING", "Dangerous function call detected: review carefully"),
    (_COMPLEXITY_SIGNALS, "complexity", "INFO", "High complexity signal detected: consider breaking into smaller methods"),
]


class StaticRulesStage(PipelineStage):
    """Zero-cost pre-review stage that checks static patterns."""

    @property
    def name(self) -> str:
        return "static_rules"

    async def execute(self, context: PipelineContext) -> PipelineContext:
        logger.info("Pipeline Stage [static_rules]: Scanning diff with static patterns")
        issues: list[IssuePayload] = []

        for line_no, line in enumerate(context.diff_text.split("\n"), 1):
            # Only scan added lines (diff lines starting with +, not ++)
            if not line.startswith("+") or line.startswith("+++"):
                continue

            content = line[1:]  # strip the leading +
            for pattern, issue_type, severity, message in _RULES:
                if pattern.search(content):
                    # Try to extract file path from diff header context
                    file_path = self._extract_file_path(context.diff_text, line_no)
                    issues.append(IssuePayload(
                        severity=severity,
                        file=file_path,
                        line=line_no,
                        type=issue_type,
                        message=message,
                        suggestion="Review and fix before merging.",
                    ))

        if issues:
            context.review_results["static_rules"] = [
                IssuePayload(**i.model_dump()) for i in issues
            ]
            # Also add to final_issues since static rules bypass LLM
            context.final_issues.extend(issues)
            logger.info("Static rules found %d issues", len(issues))
        else:
            logger.info("Static rules: no issues found")

        return context

    @staticmethod
    def _extract_file_path(diff_text: str, target_line: int) -> str:
        """Best-effort extraction of file path from diff headers."""
        current_file = ""
        for i, line in enumerate(diff_text.split("\n"), 1):
            if line.startswith("--- a/") or line.startswith("--- /dev/null"):
                continue
            if line.startswith("+++ b/"):
                current_file = line[6:]
            if i >= target_line:
                break
        return current_file
