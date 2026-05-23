"""PipelineStage abstraction and PipelineContext."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.callbacks import BaseCallbackHandler

from diffguard_agent.models.schemas import IssuePayload

if TYPE_CHECKING:
    from diffguard_agent.tools.tool_client import JavaToolClient


class TokenUsageTracker(BaseCallbackHandler):
    """Callback that accumulates token usage across all LLM calls."""

    def __init__(self) -> None:
        self.total_prompt_tokens: int = 0
        self.total_completion_tokens: int = 0
        self.total_tokens: int = 0
        self.call_count: int = 0

    def on_llm_end(self, response, *, run_id, parent_run_id=None, **kwargs):
        try:
            usage = response.llm_output.get("token_usage", {}) if response.llm_output else {}
            if not usage and hasattr(response, "generations") and response.generations:
                gen = response.generations[0][0]
                if hasattr(gen, "generation_info") and gen.generation_info:
                    usage = gen.generation_info.get("usage", {}) or gen.generation_info.get("token_usage", {})
            self.total_prompt_tokens += usage.get("prompt_tokens", 0)
            self.total_completion_tokens += usage.get("completion_tokens", 0)
            self.total_tokens += usage.get("total_tokens", 0)
            self.call_count += 1
        except Exception:
            pass


@dataclass
class PipelineInput:
    """Immutable input data set once at pipeline start."""
    diff_text: str = ""
    llm: BaseChatModel | None = None
    tool_client: JavaToolClient | None = None
    token_tracker: TokenUsageTracker = field(default_factory=TokenUsageTracker)
    historical_context: str = ""
    file_diffs: dict[str, str] = field(default_factory=dict)


@dataclass
class SummaryOutput:
    """Stage 1 output: diff summary and file routing."""
    summary: str = ""
    changed_files: list[str] = field(default_factory=list)
    change_types: list[str] = field(default_factory=list)
    estimated_risk_level: int = 3
    file_groups: dict[str, list[str]] = field(default_factory=dict)


@dataclass
class ReviewOutput:
    """Stage 2 output: per-reviewer results."""
    review_results: dict[str, Any] = field(default_factory=dict)


@dataclass
class AggregationOutput:
    """Stage 3 + Stage 4 output: merged issues and final verdict."""
    final_issues: list[IssuePayload] = field(default_factory=list)
    final_summary: str = ""
    has_critical: bool = False
    highlights: list[str] = field(default_factory=list)
    test_suggestions: list[str] = field(default_factory=list)
    filter_stats: Any = None


@dataclass
class PipelineContext:
    """Mutable context passed between pipeline stages.

    Composed of sub-objects for each pipeline phase to avoid a god object.
    """
    input: PipelineInput = field(default_factory=PipelineInput)
    summary: SummaryOutput = field(default_factory=SummaryOutput)
    review: ReviewOutput = field(default_factory=ReviewOutput)
    aggregation: AggregationOutput = field(default_factory=AggregationOutput)


class PipelineStage(ABC):
    """Abstract base for a single pipeline stage."""

    @property
    @abstractmethod
    def name(self) -> str:
        """Stage identifier."""

    @abstractmethod
    async def execute(self, context: PipelineContext) -> PipelineContext:
        """Execute this stage, reading from and writing to the context."""
