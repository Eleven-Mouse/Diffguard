"""Base data classes for agent results."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

from app.models.schemas import IssuePayload


@dataclass
class AgentReviewResult:
    """Result returned by an Agent after completing a review.

    This class is used as a common interface across all agents
    (builtin and custom) so callers can handle results uniformly.
    """

    has_critical: bool = False
    summary: str = ""
    issues: list[IssuePayload] = field(default_factory=list)
    metadata: dict = field(default_factory=dict)

    @property
    def critical_count(self) -> int:
        """Number of CRITICAL severity issues."""
        return sum(1 for i in self.issues if i.severity.upper() == "CRITICAL")

    @property
    def warning_count(self) -> int:
        """Number of WARNING severity issues."""
        return sum(1 for i in self.issues if i.severity.upper() == "WARNING")

    def to_dict(self) -> dict:
        """Convert to a plain dictionary for serialization."""
        return {
            "has_critical": self.has_critical,
            "summary": self.summary,
            "issue_count": len(self.issues),
            "issues": [
                {
                    "severity": i.severity,
                    "file": i.file,
                    "line": i.line,
                    "type": i.type,
                    "message": i.message,
                    "suggestion": i.suggestion,
                    "confidence": i.confidence,
                }
                for i in self.issues
            ],
            "metadata": self.metadata,
        }