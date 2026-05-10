"""Pipeline orchestrator - composable multi-stage code review pipeline.

The pipeline is built from ``PipelineStage`` instances that are executed
sequentially.  The default configuration uses three stages (summary -> review
-> aggregation), but callers can customise the stage list.
"""

from __future__ import annotations

import logging

from app.agent.llm_utils import create_llm
from app.agent.pipeline.stages.base import PipelineContext, PipelineStage
from app.agent.pipeline.stages.summary import SummaryStage
from app.agent.pipeline.stages.reviewer import ReviewerStage
from app.agent.pipeline.stages.aggregation import AggregationStage
from app.agent.pipeline.stages.false_positive_filter import FalsePositiveFilterStage
from app.models.schemas import (
    ReviewRequest,
    ReviewResponse,
    ReviewStatus,
)
from app.tools.tool_client import JavaToolClient, create_tool_session, destroy_tool_session
from app.config import settings

logger = logging.getLogger(__name__)


# --- Default pipeline builder ---


def build_default_pipeline() -> list[PipelineStage]:
    """Build the standard 4-stage review pipeline."""
    return [
        SummaryStage(),
        ReviewerStage(),
        AggregationStage(),
        FalsePositiveFilterStage(),
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
        llm = create_llm(req)
        diff_text = "\n".join(e.content for e in req.diff_entries)

        tool_client: JavaToolClient | None = None

        try:
            # Tool Server is optional — skip when no URL is configured
            # (e.g. GitHub Action mode without the Java gateway)
            if req.tool_server_url:
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
                try:
                    await destroy_tool_session(tool_client)
                except Exception:
                    pass
