"""Base data classes for agent results."""

from __future__ import annotations

import json
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any

from diffguard_agent.models.schemas import IssuePayload


@dataclass
class AgentReviewResult:
    """Result returned by an Agent after completing a review.

    This class is used as a common interface across all agents
    (builtin and custom) so callers can handle results uniformly.
    """

    has_critical: bool = False
    summary: str = ""
    issues: list[IssuePayload] = field(default_factory=list)
    highlights: list[str] = field(default_factory=list)
    confidence: float = 1.0
    metadata: dict[str, Any] = field(default_factory=dict)

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
            "highlights": self.highlights,
            "confidence": self.confidence,
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

    def model_dump_json(self) -> str:
        """Compatibility helper for legacy tests expecting Pydantic-like API."""
        return json.dumps(
            {
                "has_critical": self.has_critical,
                "summary": self.summary,
                "issues": [i.model_dump() for i in self.issues],
                "highlights": self.highlights,
                "confidence": self.confidence,
                "metadata": self.metadata,
            }
        )


class ReviewAgent(ABC):
    """Abstract contract for review agents."""

    @property
    @abstractmethod
    def name(self) -> str:
        """Agent name."""

    @property
    @abstractmethod
    def description(self) -> str:
        """Agent description."""

    @property
    def default_weight(self) -> float:
        """Default weight used by weighted aggregation strategies."""
        return 1.0

    @abstractmethod
    async def review(
        self,
        llm: Any,
        diff_text: str,
        tool_client: Any,
        focus_areas: list[str] | None = None,
        additional_rules: list[str] | None = None,
        max_iterations: int = 8,
    ) -> AgentReviewResult:
        """Run review and return findings."""
