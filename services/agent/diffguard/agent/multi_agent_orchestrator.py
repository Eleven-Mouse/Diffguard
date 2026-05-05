"""Multi-agent orchestrator - parallel specialized review agents."""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from app.agent.pipeline_orchestrator import _create_llm, _load_prompt
from app.agent.strategy_planner import AgentType, StrategyPlanner
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

# Map agent type to its system prompt file
_AGENT_PROMPT_MAP: dict[AgentType, str] = {
    AgentType.SECURITY: "reviewagents/security-system.txt",
    AgentType.PERFORMANCE: "reviewagents/performance-system.txt",
    AgentType.ARCHITECTURE: "reviewagents/architecture-system.txt",
}

# Architecture agent gets extra tools
_ARCHITECTURE_EXTRA_TOOLS = True


async def _run_specialized_agent(
    llm: Any,
    agent_type: AgentType,
    system_prompt: str,
    diff_text: str,
    tool_client: JavaToolClient,
    max_iterations: int = 8,
) -> ReviewResponse:
    """Run a single specialized ReAct agent."""
    from langchain.agents import AgentExecutor, create_tool_calling_agent
    from langchain_core.prompts import ChatPromptTemplate

    tools = [
        make_file_content_tool(tool_client),
        make_diff_context_tool(tool_client),
        make_method_definition_tool(tool_client),
        make_call_graph_tool(tool_client),
        make_related_files_tool(tool_client),
        make_semantic_search_tool(tool_client),
    ]

    user_tpl = _load_prompt("react-user.txt")
    user = user_tpl.replace("{{diff}}", diff_text)

    prompt = ChatPromptTemplate.from_messages(
        [
            ("system", system_prompt),
            ("human", user),
            ("placeholder", "{agent_scratchpad}"),
        ]
    )

    agent = create_tool_calling_agent(llm, tools, prompt)
    executor = AgentExecutor(
        agent=agent, tools=tools, max_iterations=max_iterations, verbose=True
    )

    raw = await executor.ainvoke({"input": user})
    output_text = raw.get("output", "")

    # Parse the agent output into issues
    from pydantic import BaseModel, Field

    class AgentOutput(BaseModel):
        has_critical: bool = False
        summary: str = ""
        issues: list[IssuePayload] = Field(default_factory=list)
        highlights: list[str] = Field(default_factory=list)
        test_suggestions: list[str] = Field(default_factory=list)

    try:
        parsed = AgentOutput.model_validate_json(output_text)
    except Exception:
        parsed_llm = llm.with_structured_output(AgentOutput)
        parsed = await parsed_llm.ainvoke(
            [("human", f"Parse into JSON:\n{output_text}")]
        )

    return parsed


class MultiAgentOrchestrator:
    """Strategy-planned parallel specialized agents."""

    def __init__(self, request: ReviewRequest) -> None:
        self.request = request

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
            )

            # Compute strategy
            planner = StrategyPlanner()
            strategy = planner.plan(req.diff_entries)
            logger.info("Strategy: %s (weights: %s)", strategy.name, strategy.agent_weights)

            enabled = strategy.get_enabled_agents()
            if not enabled:
                return ReviewResponse(
                    request_id=req.request_id,
                    status=ReviewStatus.COMPLETED,
                    summary="No agents enabled for this diff profile.",
                )

            # Run agents in parallel
            tasks = []
            for agent_type in enabled:
                prompt_file = _AGENT_PROMPT_MAP[agent_type]
                system_prompt = _load_prompt(prompt_file)

                # Append focus areas and rules from strategy
                if strategy.focus_areas:
                    system_prompt += "\n\nAdditional focus areas:\n" + "\n".join(
                        f"- {a}" for a in strategy.focus_areas
                    )
                if strategy.additional_rules:
                    system_prompt += "\n\nAdditional rules:\n" + "\n".join(
                        f"- {r}" for r in strategy.additional_rules
                    )

                tasks.append(
                    _run_specialized_agent(llm, agent_type, system_prompt, diff_text, tool_client)
                )

            results = await asyncio.gather(*tasks, return_exceptions=True)

            # Aggregate: deduplicate issues
            all_issues: list[IssuePayload] = []
            has_critical = False
            seen_keys: set[str] = set()

            for result in results:
                if isinstance(result, Exception):
                    logger.warning("Agent failed: %s", result)
                    continue
                has_critical = has_critical or result.has_critical
                for issue in result.issues:
                    key = f"{issue.file}:{issue.line}:{issue.type}"
                    if key not in seen_keys:
                        seen_keys.add(key)
                        all_issues.append(issue)

            summary = f"Reviewed with {len(enabled)} agents: {', '.join(a.value for a in enabled)}."

            return ReviewResponse(
                request_id=req.request_id,
                status=ReviewStatus.COMPLETED,
                has_critical_flag=has_critical,
                issues=all_issues,
                summary=summary,
            )

        except Exception as e:
            logger.exception("Multi-agent review failed")
            return ReviewResponse(
                request_id=req.request_id,
                status=ReviewStatus.FAILED,
                error=str(e),
            )
        finally:
            if tool_client:
                await destroy_tool_session(tool_client)
