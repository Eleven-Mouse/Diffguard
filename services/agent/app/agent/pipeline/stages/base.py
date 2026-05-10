"""PipelineStage abstraction and PipelineContext."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from langchain_core.language_models.chat_models import BaseChatModel

from app.models.schemas import IssuePayload

if TYPE_CHECKING:
    from app.tools.tool_client import JavaToolClient


@dataclass
class PipelineContext:
    """Mutable context passed between pipeline stages."""
    # Input (set once)
    diff_text: str = ""
    llm: BaseChatModel | None = None
    tool_client: JavaToolClient | None = None

    # Stage 1 output
    summary: str = ""
    changed_files: list[str] = field(default_factory=list)
    change_types: list[str] = field(default_factory=list)
    estimated_risk_level: int = 3

    # Stage 2 output (keyed by reviewer name)
    review_results: dict[str, Any] = field(default_factory=dict)

    # Stage 3 output
    final_issues: list[IssuePayload] = field(default_factory=list)
    final_summary: str = ""
    has_critical: bool = False
    highlights: list[str] = field(default_factory=list)
    test_suggestions: list[str] = field(default_factory=list)

    # False positive filter output
    filter_stats: Any = None


class PipelineStage(ABC):
    """Abstract base for a single pipeline stage."""

    @property
    @abstractmethod
    def name(self) -> str:
        """Stage identifier."""

    @abstractmethod
    async def execute(self, context: PipelineContext) -> PipelineContext:
        """Execute this stage, reading from and writing to the context."""
