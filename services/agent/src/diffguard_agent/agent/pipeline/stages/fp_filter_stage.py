"""Pipeline stage: two-stage false positive filter.

Runs after aggregation so it operates on the final merged issue set.
"""

from __future__ import annotations

import logging

from diffguard_agent.agent.false_positive_filter import FindingsFilter
from diffguard_agent.agent.pipeline.stages.base import PipelineContext, PipelineStage

logger = logging.getLogger(__name__)


class FalsePositiveFilterStage(PipelineStage):
    """Filter false positive findings from the pipeline output.

    Issues below the confidence threshold are marked as excluded
    (not deleted) so consumers can optionally reveal them.
    """

    def __init__(
        self,
        confidence_threshold: float = 0.5,
        enable_llm_verification: bool = False,
    ) -> None:
        self._threshold = confidence_threshold
        self._verify = enable_llm_verification

    @property
    def name(self) -> str:
        return "false_positive_filter"

    async def execute(self, context: PipelineContext) -> PipelineContext:
        fp_filter = FindingsFilter(
            confidence_threshold=self._threshold,
            enable_llm_verification=self._verify,
            llm=context.input.llm,
            diff_context=context.input.diff_text,
        )

        filtered, stats = await fp_filter.filter_issues(context.aggregation.final_issues)
        context.aggregation.final_issues = filtered
        context.aggregation.filter_stats = stats

        visible = sum(
            1 for i in filtered if not i.filter_metadata.get("excluded", False)
        )
        logger.info(
            "False positive filter: %d input, %d excluded, %d passed",
            stats.total_input,
            stats.excluded_by_hard_rules,
            visible,
        )
        return context
