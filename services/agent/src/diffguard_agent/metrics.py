"""Lightweight metrics collector for pipeline observability.

Records structured metrics per review: stage latencies, issue counts,
token usage, and LLM call statistics.  Designed to be pluggable —
replace the sink with Prometheus / OpenTelemetry in production.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class ReviewMetrics:
    """Accumulates metrics for a single review run."""

    request_id: str = ""
    started_at: float = field(default_factory=time.monotonic)

    # Per-stage latency (ms)
    stage_durations_ms: dict[str, float] = field(default_factory=dict)

    # LLM call stats
    llm_call_count: int = 0
    llm_total_duration_ms: float = 0.0
    llm_prompt_tokens: int = 0
    llm_completion_tokens: int = 0
    llm_total_tokens: int = 0

    # Review stats
    total_issues_raw: int = 0
    total_issues_after_filter: int = 0
    critical_count: int = 0
    warning_count: int = 0
    info_count: int = 0

    # FP filter stats
    fp_excluded_by_hard_rules: int = 0
    fp_excluded_by_llm: int = 0

    # Chunking
    chunk_count: int = 1

    # Outcome
    status: str = ""
    total_duration_ms: float = 0.0

    def record_stage(self, stage_name: str, duration_ms: float) -> None:
        self.stage_durations_ms[stage_name] = duration_ms

    def record_llm_call(self, duration_ms: float, prompt_tokens: int = 0,
                        completion_tokens: int = 0) -> None:
        self.llm_call_count += 1
        self.llm_total_duration_ms += duration_ms
        self.llm_prompt_tokens += prompt_tokens
        self.llm_completion_tokens += completion_tokens

    def record_issues(self, issues: list, after_filter: list | None = None) -> None:
        self.total_issues_raw = len(issues)
        visible = after_filter if after_filter is not None else issues
        self.total_issues_after_filter = sum(
            1 for i in visible if not i.filter_metadata.get("excluded", False)
        )
        for issue in visible:
            if issue.filter_metadata.get("excluded"):
                continue
            sev = issue.severity.upper()
            if sev == "CRITICAL":
                self.critical_count += 1
            elif sev == "WARNING":
                self.warning_count += 1
            else:
                self.info_count += 1

    def finish(self, status: str = "completed") -> None:
        self.status = status
        self.total_duration_ms = (time.monotonic() - self.started_at) * 1000
        logger.info(
            "Review metrics [%s]: status=%s duration=%.0fms stages=%s "
            "issues=%d/%d llm_calls=%d tokens=%d",
            self.request_id,
            self.status,
            self.total_duration_ms,
            self.stage_durations_ms,
            self.total_issues_after_filter,
            self.total_issues_raw,
            self.llm_call_count,
            self.llm_total_tokens,
        )

    def to_dict(self) -> dict:
        return {
            "request_id": self.request_id,
            "status": self.status,
            "total_duration_ms": round(self.total_duration_ms, 1),
            "stage_durations_ms": {k: round(v, 1) for k, v in self.stage_durations_ms.items()},
            "llm_call_count": self.llm_call_count,
            "llm_total_duration_ms": round(self.llm_total_duration_ms, 1),
            "llm_tokens": self.llm_total_tokens,
            "issues_raw": self.total_issues_raw,
            "issues_visible": self.total_issues_after_filter,
            "critical": self.critical_count,
            "warning": self.warning_count,
            "info": self.info_count,
        }
