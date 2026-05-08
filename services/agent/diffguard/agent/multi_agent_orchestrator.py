"""Multi-agent orchestrator - strategy-planned parallel specialized agents.

Agents are discovered via ``AgentRegistry`` and selected by the
``StrategyPlanner`` based on the diff profile.  Cross-agent knowledge
sharing is enabled through ``AgentMemory``.
"""

from __future__ import annotations

import asyncio
import logging

from app.agent.base import AgentReviewResult
from app.agent.memory import AgentMemory
from app.agent.pipeline_orchestrator import _create_llm
from app.agent.registry import AgentRegistry
from app.agent.strategy_planner import StrategyPlanner
from app.models.schemas import (
    ReviewRequest,
    ReviewResponse,
    ReviewStatus,
)
from app.tools.tool_client import JavaToolClient, create_tool_session, destroy_tool_session
from app.config import settings

logger = logging.getLogger(__name__)

# Ensure built-in agents are registered on import
import app.agent.builtin_agents  # noqa: F401


class MultiAgentOrchestrator:
    """Strategy-planned parallel specialized agents.

    Uses ``StrategyPlanner`` to determine which agents run and with what
    parameters, ``AgentRegistry`` to instantiate them, and ``AgentMemory``
    to share findings across concurrent agents.
    """

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
                tool_secret=settings.DIFFGUARD_TOOL_SECRET,
            )

            # Compute strategy
            planner = StrategyPlanner()
            strategy = planner.plan(req.diff_entries)
            logger.info("Strategy: %s (weights: %s)", strategy.name, strategy.agent_weights)

            # Create enabled agent instances from registry
            enabled_names = strategy.get_enabled_agent_names()
            if not enabled_names:
                return ReviewResponse(
                    request_id=req.request_id,
                    status=ReviewStatus.COMPLETED,
                    summary="No agents enabled for this diff profile.",
                )

            agents = []
            for name in enabled_names:
                try:
                    agents.append(AgentRegistry.create(name))
                except ValueError:
                    logger.warning("Agent '%s' not registered, skipping", name)

            if not agents:
                return ReviewResponse(
                    request_id=req.request_id,
                    status=ReviewStatus.COMPLETED,
                    summary="No agents available for this diff profile.",
                )

            # Shared memory for cross-agent context
            memory = AgentMemory()

            # Run agents in parallel, each receiving the shared memory
            tasks = [
                self._run_agent(agent, llm, diff_text, tool_client, strategy, memory)
                for agent in agents
            ]
            results = await asyncio.gather(*tasks, return_exceptions=True)

            # Aggregate: deduplicate issues
            all_issues = []
            has_critical = False
            seen_keys: set[str] = set()

            for agent, result in zip(agents, results):
                if isinstance(result, Exception):
                    logger.warning("Agent '%s' failed: %s", agent.name, result)
                    continue

                has_critical = has_critical or result.has_critical
                for issue in result.issues:
                    key = f"{issue.file}:{issue.line}:{issue.type}"
                    if key not in seen_keys:
                        seen_keys.add(key)
                        all_issues.append(issue)

                memory.add_result(agent.name, result)

            agent_names = [a.name for a in agents]
            summary = f"Reviewed with {len(agents)} agents: {', '.join(agent_names)}."

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

    async def _run_agent(
        self,
        agent: Any,
        llm: Any,
        diff_text: str,
        tool_client: JavaToolClient,
        strategy: Any,
        memory: AgentMemory,
    ) -> AgentReviewResult:
        """Run a single agent with strategy parameters and memory context."""
        # Inject findings from other agents that completed earlier
        other_findings = memory.get_findings_for(agent.name)
        focus_areas = list(strategy.focus_areas)
        if other_findings:
            focus_areas.append(f"Cross-agent findings:\n{other_findings}")

        result = await agent.review(
            llm=llm,
            diff_text=diff_text,
            tool_client=tool_client,
            focus_areas=focus_areas or None,
            additional_rules=strategy.additional_rules or None,
        )

        memory.add_result(agent.name, result)
        return result
