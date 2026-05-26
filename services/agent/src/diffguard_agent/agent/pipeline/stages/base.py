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
            usage = self._extract_usage(response)
            prompt_tokens = int(usage.get("prompt_tokens", 0) or 0)
            completion_tokens = int(usage.get("completion_tokens", 0) or 0)
            total_tokens = int(usage.get("total_tokens", 0) or 0)
            if total_tokens <= 0:
                total_tokens = prompt_tokens + completion_tokens

            self.total_prompt_tokens += prompt_tokens
            self.total_completion_tokens += completion_tokens
            self.total_tokens += total_tokens
            self.call_count += 1
        except Exception:
            pass

    @staticmethod
    def _extract_usage(response) -> dict[str, int]:
        """Extract token usage across provider-specific response shapes."""
        llm_output = getattr(response, "llm_output", None) or {}

        # Priority 1: canonical llm_output usage.
        usage = llm_output.get("token_usage", {}) or llm_output.get("usage", {})
        if usage:
            return TokenUsageTracker._normalize_usage_keys(usage)

        if hasattr(response, "generations") and response.generations:
            gen = response.generations[0][0]

            # Priority 2: generation_info usage.
            gen_info = getattr(gen, "generation_info", None) or {}
            usage = gen_info.get("usage", {}) or gen_info.get("token_usage", {})
            if usage:
                return TokenUsageTracker._normalize_usage_keys(usage)

            # Priority 3: message usage_metadata (newer LangChain providers).
            message = getattr(gen, "message", None)
            usage_meta = getattr(message, "usage_metadata", None) if message is not None else None
            if usage_meta:
                return TokenUsageTracker._normalize_usage_keys(usage_meta)

        return {}

    @staticmethod
    def _normalize_usage_keys(usage: dict) -> dict[str, int]:
        """Map provider-specific usage keys to prompt/completion/total."""
        prompt = (
            usage.get("prompt_tokens")
            or usage.get("input_tokens")
            or usage.get("prompt_token_count")
            or 0
        )
        completion = (
            usage.get("completion_tokens")
            or usage.get("output_tokens")
            or usage.get("candidate_tokens")
            or 0
        )
        total = usage.get("total_tokens") or usage.get("total_token_count") or 0
        return {
            "prompt_tokens": int(prompt or 0),
            "completion_tokens": int(completion or 0),
            "total_tokens": int(total or 0),
        }


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
