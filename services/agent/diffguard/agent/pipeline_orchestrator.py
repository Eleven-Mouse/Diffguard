"""Pipeline orchestrator - 3-stage code review pipeline."""

from __future__ import annotations

import logging
from typing import Any

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.prompts import ChatPromptTemplate
from pydantic import BaseModel, Field

from app.models.schemas import (
    IssuePayload,
    ReviewRequest,
    ReviewResponse,
    ReviewStatus,
)
from app.tools.call_graph import make_call_graph_tool
from app.tools.diff_context import make_diff_context_tool
from app.tools.file_content import make_file_content_tool
from app.tools.method_definition import make_method_definition_tool
from app.tools.related_files import make_related_files_tool
from app.tools.semantic_search import make_semantic_search_tool
from app.tools.tool_client import JavaToolClient, create_tool_session, destroy_tool_session

logger = logging.getLogger(__name__)

_PROMPTS_DIR = "app/prompts"


# --- Structured output schemas ---


class DiffSummary(BaseModel):
    summary: str = ""
    changed_files: list[str] = Field(default_factory=list)
    change_types: list[str] = Field(default_factory=list)
    estimated_risk_level: int = Field(ge=1, le=5, default=3)


class TargetedReviewResult(BaseModel):
    summary: str = ""
    issues: list[IssuePayload] = Field(default_factory=list)


class AggregatedReview(BaseModel):
    has_critical: bool = False
    summary: str = ""
    issues: list[IssuePayload] = Field(default_factory=list)
    highlights: list[str] = Field(default_factory=list)
    test_suggestions: list[str] = Field(default_factory=list)


# --- LLM factory ---


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


# --- Pipeline stages ---


async def _stage_summary(llm: BaseChatModel, diff_text: str) -> DiffSummary:
    """Stage 1: Summarize the diff."""
    system = _load_prompt("pipeline/diff-summary-system.txt")
    user_tpl = _load_prompt("pipeline/diff-summary-user.txt")
    user = user_tpl.replace("{{diff}}", diff_text)

    structured_llm = llm.with_structured_output(DiffSummary)
    result = await structured_llm.ainvoke(
        [
            ("system", system),
            ("human", user),
        ]
    )
    return result


async def _stage_review(
    llm: BaseChatModel,
    system_prompt_file: str,
    user_prompt_file: str,
    summary: str,
    diff_text: str,
    tool_client: JavaToolClient,
) -> TargetedReviewResult:
    """Stage 2: Run a domain-specific reviewer with tools."""
    import asyncio

    from langchain.agents import AgentExecutor, create_tool_calling_agent

    system = _load_prompt(system_prompt_file)
    user_tpl = _load_prompt(user_prompt_file)
    user = user_tpl.replace("{{summary}}", summary).replace("{{diff}}", diff_text)

    tools = [
        make_file_content_tool(tool_client),
        make_diff_context_tool(tool_client),
        make_method_definition_tool(tool_client),
        make_call_graph_tool(tool_client),
        make_related_files_tool(tool_client),
        make_semantic_search_tool(tool_client),
    ]

    prompt = ChatPromptTemplate.from_messages(
        [
            ("system", system),
            ("human", user),
            ("placeholder", "{agent_scratchpad}"),
        ]
    )

    agent = create_tool_calling_agent(llm, tools, prompt)
    executor = AgentExecutor(agent=agent, tools=tools, max_iterations=8, verbose=True)

    raw = await executor.ainvoke({"input": user})

    # Try to extract structured output from the agent's final answer
    output_text = raw.get("output", "")
    try:
        return TargetedReviewResult.model_validate_json(output_text)
    except Exception:
        # Fallback: use the LLM to parse the output
        parse_prompt = (
            "Parse the following review output into structured JSON matching this schema: "
            "{summary: string, issues: [{severity, file, line, type, message, suggestion}]}. "
            f"Output:\n{output_text}"
        )
        structured = llm.with_structured_output(TargetedReviewResult)
        return await structured.ainvoke([("human", parse_prompt)])


async def _stage_aggregate(
    llm: BaseChatModel,
    summary: str,
    security_result: str,
    logic_result: str,
    quality_result: str,
) -> AggregatedReview:
    """Stage 3: Aggregate all review results."""
    system = _load_prompt("pipeline/aggregation-system.txt")
    user_tpl = _load_prompt("pipeline/aggregation-user.txt")
    user = (
        user_tpl.replace("{{summary}}", summary)
        .replace("{{securityResult}}", security_result)
        .replace("{{logicResult}}", logic_result)
        .replace("{{qualityResult}}", quality_result)
    )

    structured_llm = llm.with_structured_output(AggregatedReview)
    return await structured_llm.ainvoke(
        [
            ("system", system),
            ("human", user),
        ]
    )


# --- Orchestrator ---


class PipelineOrchestrator:
    """Three-stage pipeline: summary -> parallel review -> aggregation."""

    def __init__(self, request: ReviewRequest) -> None:
        self.request = request

    async def run(self) -> ReviewResponse:
        req = self.request
        llm = _create_llm(req)
        diff_text = "\n".join(e.content for e in req.diff_entries)

        tool_client: JavaToolClient | None = None

        try:
            # Create tool session
            tool_client = await create_tool_session(
                req.tool_server_url,
                req.diff_entries,
                req.project_dir,
                req.allowed_files,
            )

            # Stage 1: Diff summary
            logger.info("Pipeline Stage 1: Summarizing diff")
            diff_summary = await _stage_summary(llm, diff_text)
            summary_str = diff_summary.summary

            # Stage 2: Parallel reviews
            import asyncio

            logger.info("Pipeline Stage 2: Running parallel reviewers")
            sec_task = _stage_review(
                llm, "pipeline/security-system.txt", "pipeline/security-user.txt",
                summary_str, diff_text, tool_client,
            )
            logic_task = _stage_review(
                llm, "pipeline/logic-system.txt", "pipeline/logic-user.txt",
                summary_str, diff_text, tool_client,
            )
            quality_task = _stage_review(
                llm, "pipeline/quality-system.txt", "pipeline/quality-user.txt",
                summary_str, diff_text, tool_client,
            )

            sec_result, logic_result, quality_result = await asyncio.gather(
                sec_task, logic_task, quality_task
            )

            # Stage 3: Aggregation
            logger.info("Pipeline Stage 3: Aggregating results")
            aggregated = await _stage_aggregate(
                llm,
                summary_str,
                sec_result.model_dump_json(),
                logic_result.model_dump_json(),
                quality_result.model_dump_json(),
            )

            return ReviewResponse(
                request_id=req.request_id,
                status=ReviewStatus.COMPLETED,
                has_critical_flag=aggregated.has_critical,
                issues=aggregated.issues,
                summary=aggregated.summary,
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
