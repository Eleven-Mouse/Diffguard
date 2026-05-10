"""Reviewer stage - runs parallel domain-specific reviews.

When ``tool_client`` is available, each reviewer runs as a tool-calling
ReAct agent (via LangChain AgentExecutor).  When ``tool_client`` is ``None``
(e.g. GitHub Action mode without the Java Tool Server), reviewers fall back
to a direct structured-LLM call — cheaper and faster, but without the
ability to explore the codebase on its own.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from pydantic import BaseModel, Field

from app.models.schemas import IssuePayload
from app.agent.pipeline.stages.base import PipelineContext, PipelineStage
from app.agent.llm_utils import load_prompt

logger = logging.getLogger(__name__)


class _TargetedReviewResult(BaseModel):
    summary: str = ""
    issues: list[IssuePayload] = Field(default_factory=list)


class ReviewerStage(PipelineStage):
    """Runs multiple domain-specific reviewers in parallel."""

    @property
    def name(self) -> str:
        return "review"

    def __init__(self, reviewers: list[tuple[str, str, str]] | None = None) -> None:
        self._reviewers = reviewers or [
            ("security", "pipeline/security-system.txt", "pipeline/security-user.txt"),
            ("logic", "pipeline/logic-system.txt", "pipeline/logic-user.txt"),
            ("quality", "pipeline/quality-system.txt", "pipeline/quality-user.txt"),
        ]

    async def execute(self, context: PipelineContext) -> PipelineContext:
        logger.info("Pipeline Stage [review]: Running %d reviewers", len(self._reviewers))

        tasks = [
            self._run_reviewer(
                context.llm, name, sys_file, usr_file,
                context.summary, context.diff_text, context.tool_client,
            )
            for name, sys_file, usr_file in self._reviewers
        ]

        results = await asyncio.gather(*tasks, return_exceptions=True)

        for (name, _, _), result in zip(self._reviewers, results):
            if isinstance(result, Exception):
                logger.warning("Reviewer '%s' failed: %s", name, result)
                context.review_results[name] = _TargetedReviewResult(
                    summary=f"Reviewer failed: {result}"
                ).model_dump_json()
            else:
                context.review_results[name] = result.model_dump_json()

        logger.info("All reviewers completed: %s", list(context.review_results.keys()))
        return context

    async def _run_reviewer(
        self,
        llm: Any,
        name: str,
        system_prompt_file: str,
        user_prompt_file: str,
        summary: str,
        diff_text: str,
        tool_client: Any,
    ) -> _TargetedReviewResult:
        system = load_prompt(system_prompt_file)
        user_tpl = load_prompt(user_prompt_file)
        user = user_tpl.replace("{{summary}}", summary).replace("{{diff}}", diff_text)

        if tool_client is not None:
            return await self._run_with_tools(llm, system, user, tool_client)
        return await self._run_direct(llm, system, user)

    async def _run_with_tools(
        self, llm: Any, system: str, user: str, tool_client: Any,
    ) -> _TargetedReviewResult:
        from langchain.agents import AgentExecutor, create_tool_calling_agent
        from langchain_core.prompts import ChatPromptTemplate

        from app.tools.definitions import (
            make_call_graph_tool,
            make_diff_context_tool,
            make_file_content_tool,
            make_method_definition_tool,
            make_related_files_tool,
            make_semantic_search_tool,
        )

        tools = [
            make_file_content_tool(tool_client),
            make_diff_context_tool(tool_client),
            make_method_definition_tool(tool_client),
            make_call_graph_tool(tool_client),
            make_related_files_tool(tool_client),
            make_semantic_search_tool(tool_client),
        ]

        prompt = ChatPromptTemplate.from_messages([
            ("system", system),
            ("human", user),
            ("placeholder", "{agent_scratchpad}"),
        ])

        agent = create_tool_calling_agent(llm, tools, prompt)
        executor = AgentExecutor(agent=agent, tools=tools, max_iterations=8, verbose=True)

        raw = await executor.ainvoke({"input": user})
        output_text = raw.get("output", "")

        try:
            return _TargetedReviewResult.model_validate_json(output_text)
        except Exception:
            return await self._parse_fallback(llm, output_text)

    async def _run_direct(
        self, llm: Any, system: str, user: str,
    ) -> _TargetedReviewResult:
        structured_llm = llm.with_structured_output(_TargetedReviewResult)
        return await structured_llm.ainvoke([("system", system), ("human", user)])

    async def _parse_fallback(self, llm: Any, output_text: str) -> _TargetedReviewResult:
        parse_prompt = (
            "Parse the following review output into structured JSON matching this schema: "
            "{summary: string, issues: [{severity, file, line, type, message, suggestion}]}. "
            f"Output:\n{output_text}"
        )
        structured = llm.with_structured_output(_TargetedReviewResult)
        return await structured.ainvoke([("human", parse_prompt)])
