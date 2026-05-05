"""Performance review agent - detects performance bottlenecks and resource issues."""

from __future__ import annotations

from typing import Any

from app.agent.base import AgentReviewResult, ReviewAgent
from app.agent.registry import AgentRegistry
from app.agent.builtin_agents.security import _parse_agent_output
from app.tools.tool_client import JavaToolClient
from app.tools.definitions import (
    make_call_graph_tool,
    make_diff_context_tool,
    make_file_content_tool,
    make_method_definition_tool,
    make_related_files_tool,
    make_semantic_search_tool,
)


@AgentRegistry.register("performance")
class PerformanceAgent(ReviewAgent):

    @property
    def name(self) -> str:
        return "performance"

    @property
    def description(self) -> str:
        return "Detects performance issues: N+1 queries, memory leaks, inefficient algorithms, concurrency problems."

    @property
    def default_weight(self) -> float:
        return 1.0

    async def review(
        self,
        llm: Any,
        diff_text: str,
        tool_client: JavaToolClient,
        focus_areas: list[str] | None = None,
        additional_rules: list[str] | None = None,
        max_iterations: int = 8,
    ) -> AgentReviewResult:
        from langchain.agents import AgentExecutor, create_tool_calling_agent
        from langchain_core.prompts import ChatPromptTemplate

        from app.agent.pipeline_orchestrator import _load_prompt

        system = _load_prompt("reviewagents/performance-system.txt")
        if focus_areas:
            system += "\n\nAdditional focus areas:\n" + "\n".join(f"- {a}" for a in focus_areas)
        if additional_rules:
            system += "\n\nAdditional rules:\n" + "\n".join(f"- {r}" for r in additional_rules)

        user_tpl = _load_prompt("react-user.txt")
        user = user_tpl.replace("{{diff}}", diff_text)

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
        executor = AgentExecutor(agent=agent, tools=tools, max_iterations=max_iterations, verbose=True)

        raw = await executor.ainvoke({"input": user})
        return _parse_agent_output(llm, raw.get("output", ""))
