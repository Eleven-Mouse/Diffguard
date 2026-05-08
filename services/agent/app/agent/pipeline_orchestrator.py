"""Pipeline orchestrator - composable multi-stage code review pipeline.

The pipeline is built from ``PipelineStage`` instances that are executed
sequentially.  The default configuration uses three stages (summary -> review
-> aggregation), but callers can customise the stage list.
"""

from __future__ import annotations

import logging
from typing import Any

from langchain_core.language_models.chat_models import BaseChatModel

from app.agent.pipeline.stages.base import PipelineContext, PipelineStage
from app.agent.pipeline.stages.summary import SummaryStage
from app.agent.pipeline.stages.reviewer import ReviewerStage
from app.agent.pipeline.stages.aggregation import AggregationStage
from app.models.schemas import (
    ReviewRequest,
    ReviewResponse,
    ReviewStatus,
)
from app.tools.tool_client import JavaToolClient, create_tool_session, destroy_tool_session
from app.config import settings

logger = logging.getLogger(__name__)

_PROMPTS_DIR = "app/prompts"


# --- Shared utilities (used by stages and agents) ---


def _create_llm(config: Any) -> BaseChatModel:
    """Create a LangChain ChatModel from the LLM config in the request."""
    llm_cfg = config.llm_config
    if llm_cfg.provider == "claude":
        from langchain_anthropic import ChatAnthropic

        kwargs: dict[str, Any] = {
            "model": llm_cfg.model,
            "max_tokens": llm_cfg.max_tokens,
            "temperature": llm_cfg.temperature,
            "timeout": llm_cfg.timeout_seconds,
        }
        if llm_cfg.api_key:
            kwargs["api_key"] = llm_cfg.api_key
        if llm_cfg.base_url:
            kwargs["anthropic_api_url"] = llm_cfg.base_url
        return ChatAnthropic(**kwargs)
    else:
        from langchain_openai import ChatOpenAI

        kwargs = {
            "model": llm_cfg.model,
            "max_tokens": llm_cfg.max_tokens,
            "temperature": llm_cfg.temperature,
            "timeout": llm_cfg.timeout_seconds,
        }
        if llm_cfg.api_key:
            kwargs["api_key"] = llm_cfg.api_key
        if llm_cfg.base_url:
            kwargs["base_url"] = llm_cfg.base_url
        return ChatOpenAI(**kwargs)


def _load_prompt(name: str) -> str:
    """Load a prompt template from the prompts directory."""
    from pathlib import Path

    path = Path(_PROMPTS_DIR) / name
    return path.read_text(encoding="utf-8")


# --- Default pipeline builder ---


def build_default_pipeline() -> list[PipelineStage]:
    """Build the standard 3-stage review pipeline."""
    return [
        SummaryStage(),
        ReviewerStage(),
        AggregationStage(),
    ]


# --- Orchestrator ---


class PipelineOrchestrator:
    """Composable multi-stage pipeline orchestrator.

    Args:
        request: The review request.
        stages: Optional list of PipelineStage instances. Defaults to the
            standard summary -> review -> aggregation pipeline.
    """

    def __init__(
        self,
        request: ReviewRequest,
        stages: list[PipelineStage] | None = None,
    ) -> None:
        self.request = request
        self.stages = stages or build_default_pipeline()

    async def run(self) -> ReviewResponse:
        req = self.request
        llm = _create_llm(req)
        diff_text = "\n".join(e.content for e in req.diff_entries)

        tool_client: JavaToolClient | None = None

        try:
            tool_client = await create_tool_session(
                req.tool_server_url,
                req.diff_entries,
                req.project_dir,
                req.allowed_files,
                tool_secret=settings.DIFFGUARD_TOOL_SECRET,
            )

            context = PipelineContext(
                diff_text=diff_text,
                llm=llm,
                tool_client=tool_client,
            )

            # Execute stages sequentially
            for stage in self.stages:
                logger.info("Executing pipeline stage: %s", stage.name)
                context = await stage.execute(context)

            return ReviewResponse(
                request_id=req.request_id,
                status=ReviewStatus.COMPLETED,
                has_critical_flag=context.has_critical,
                issues=context.final_issues,
                summary=context.final_summary,
            )

        except Exception as e:
            logger.exception("Pipeline failed")
            return ReviewResponse(
                request_id=req.request_id,
                status=ReviewStatus.FAILED,
                error=str(e),
            )
        finally:
            if tool_client:
                await destroy_tool_session(tool_client)
