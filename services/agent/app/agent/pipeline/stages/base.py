"""PipelineStage abstraction and PipelineContext."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.callbacks import BaseCallbackHandler

from app.models.schemas import IssuePayload

if TYPE_CHECKING:
    from app.tools.tool_client import JavaToolClient


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
class PipelineContext:
    """Mutable context passed between pipeline stages."""
    # Input (set once)
    diff_text: str = ""
    llm: BaseChatModel | None = None
    tool_client: JavaToolClient | None = None
    token_tracker: TokenUsageTracker = field(default_factory=TokenUsageTracker)
    historical_context: str = ""

    # Per-file diff sections (file_path → diff content)
    file_diffs: dict[str, str] = field(default_factory=dict)

    # Stage 1 output
    summary: str = ""
    changed_files: list[str] = field(default_factory=list)
    change_types: list[str] = field(default_factory=list)
    estimated_risk_level: int = 3
    # file_focus: maps reviewer name → list of file paths relevant to that reviewer
    file_groups: dict[str, list[str]] = field(default_factory=dict)

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
