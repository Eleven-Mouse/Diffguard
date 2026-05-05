"""Aggregation stage - merges all review results into a final verdict."""

from __future__ import annotations

import logging

from pydantic import BaseModel, Field

from app.models.schemas import IssuePayload
from app.agent.pipeline.stages.base import PipelineContext, PipelineStage
from app.agent.utils import load_prompt as _load_prompt

logger = logging.getLogger(__name__)


class _AggregatedReview(BaseModel):
    has_critical: bool = False
    summary: str = ""
    issues: list[IssuePayload] = Field(default_factory=list)
    highlights: list[str] = Field(default_factory=list)
    test_suggestions: list[str] = Field(default_factory=list)


class AggregationStage(PipelineStage):

    @property
    def name(self) -> str:
        return "aggregation"

    async def execute(self, context: PipelineContext) -> PipelineContext:
        logger.info("Pipeline Stage [aggregation]: Merging results")

        system = _load_prompt("pipeline/aggregation-system.txt")
        user_tpl = _load_prompt("pipeline/aggregation-user.txt")

        # Build reviewer result substitutions dynamically
        replacements = {"{{summary}}": context.summary}
        for i, (name, result_json) in enumerate(context.review_results.items()):
            replacements[f"{{{{reviewer_{i}_name}}}}"] = name
            replacements[f"{{{{reviewer_{i}_result}}}}"] = result_json

        user = user_tpl
        for key, val in replacements.items():
            user = user.replace(key, val)

        structured_llm = context.llm.with_structured_output(_AggregatedReview)
        aggregated = await structured_llm.ainvoke([("system", system), ("human", user)])

        context.final_issues = aggregated.issues
        context.final_summary = aggregated.summary
        context.has_critical = aggregated.has_critical
        context.highlights = aggregated.highlights
        context.test_suggestions = aggregated.test_suggestions

        logger.info("Aggregation complete: %d issues, has_critical=%s",
                    len(aggregated.issues), aggregated.has_critical)
        return context
