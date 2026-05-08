"""Summary stage - analyzes the diff and produces a structured summary."""

from __future__ import annotations

import logging

from pydantic import BaseModel, Field

from app.agent.pipeline.stages.base import PipelineContext, PipelineStage
from app.agent.llm_utils import load_prompt

logger = logging.getLogger(__name__)


class _DiffSummary(BaseModel):
    summary: str = ""
    changed_files: list[str] = Field(default_factory=list)
    change_types: list[str] = Field(default_factory=list)
    estimated_risk_level: int = Field(ge=1, le=5, default=3)


class SummaryStage(PipelineStage):

    @property
    def name(self) -> str:
        return "summary"

    async def execute(self, context: PipelineContext) -> PipelineContext:
        logger.info("Pipeline Stage [summary]: Analyzing diff")
        system = load_prompt("pipeline/diff-summary-system.txt")
        user_tpl = load_prompt("pipeline/diff-summary-user.txt")
        user = user_tpl.replace("{{diff}}", context.diff_text)

        structured_llm = context.llm.with_structured_output(_DiffSummary)
        result = await structured_llm.ainvoke([("system", system), ("human", user)])

        context.summary = result.summary
        context.changed_files = result.changed_files
        context.change_types = result.change_types
        context.estimated_risk_level = result.estimated_risk_level

        logger.info("Summary complete: %d files, risk=%d", len(result.changed_files), result.estimated_risk_level)
        return context
