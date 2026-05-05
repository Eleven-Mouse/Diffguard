"""ReviewAgent abstract base class - the core agent abstraction for DiffGuard."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any

from app.models.schemas import IssuePayload
from app.tools.tool_client import JavaToolClient


@dataclass
class AgentReviewResult:
    """Structured result returned by every ReviewAgent."""
    has_critical: bool = False
    summary: str = ""
    issues: list[IssuePayload] = field(default_factory=list)
    highlights: list[str] = field(default_factory=list)
    confidence: float = 1.0

    def model_dump_json(self) -> str:
        import json
        return json.dumps({
            "has_critical": self.has_critical,
            "summary": self.summary,
            "issues": [i.model_dump() for i in self.issues],
            "highlights": self.highlights,
            "confidence": self.confidence,
        })


class ReviewAgent(ABC):
    """Abstract base class for all review agents.

    Subclass this to create a specialized review agent. Register the subclass
    with ``@AgentRegistry.register("name")`` to make it discoverable.
    """

    @property
    @abstractmethod
    def name(self) -> str:
        """Unique identifier for this agent (e.g. 'security', 'performance')."""

    @property
    @abstractmethod
    def description(self) -> str:
        """Human-readable description of this agent's capabilities."""

    @property
    def default_weight(self) -> float:
        """Default strategy weight (0.0 = disabled, 1.0 = standard)."""
        return 1.0

    @abstractmethod
    async def review(
        self,
        llm: Any,
        diff_text: str,
        tool_client: JavaToolClient,
        focus_areas: list[str] | None = None,
        additional_rules: list[str] | None = None,
        max_iterations: int = 8,
    ) -> AgentReviewResult:
        """Execute the review.

        Args:
            llm: LangChain BaseChatModel instance.
            diff_text: Combined diff content.
            tool_client: Client for calling Java tool server.
            focus_areas: Optional strategy-driven focus hints.
            additional_rules: Optional extra rules from strategy.
            max_iterations: Max agent reasoning iterations.

        Returns:
            AgentReviewResult with issues and metadata.
        """
