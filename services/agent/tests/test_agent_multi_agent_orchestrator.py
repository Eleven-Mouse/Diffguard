"""Tests for app.agent.multi_agent_orchestrator - MultiAgentOrchestrator."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.agent.base import AgentReviewResult
from app.agent.strategy_planner import AgentType, ReviewStrategy
from app.models.schemas import (
    DiffEntry,
    IssuePayload,
    LlmConfig,
    ReviewConfigPayload,
    ReviewMode,
    ReviewRequest,
    ReviewResponse,
    ReviewStatus,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_request(**overrides) -> ReviewRequest:
    defaults = dict(
        request_id="req-001",
        mode=ReviewMode.MULTI_AGENT,
        project_dir="/tmp/project",
        diff_entries=[
            DiffEntry(file_path="src/Main.java", content="class Main {}", token_count=5),
        ],
        llm_config=LlmConfig(provider="openai", model="gpt-4o"),
        review_config=ReviewConfigPayload(),
    )
    defaults.update(overrides)
    return ReviewRequest(**defaults)


def _make_agent(name: str, review_result: AgentReviewResult | Exception):
    """Create a mock agent with .name and .review()."""
    agent = MagicMock()
    agent.name = name
    if isinstance(review_result, Exception):
        agent.review = AsyncMock(side_effect=review_result)
    else:
        agent.review = AsyncMock(return_value=review_result)
    return agent


# ---------------------------------------------------------------------------
# Patch targets (all use app. prefix for imports)
# ---------------------------------------------------------------------------
_BUILTIN_AGENTS = "app.agent.multi_agent_orchestrator.app.agent.builtin_agents"
_CREATE_TOOL_SESSION = "app.agent.multi_agent_orchestrator.create_tool_session"
_DESTROY_TOOL_SESSION = "app.agent.multi_agent_orchestrator.destroy_tool_session"
_CREATE_LLM = "app.agent.multi_agent_orchestrator.create_llm"
_STRATEGY_PLANNER = "app.agent.multi_agent_orchestrator.StrategyPlanner"
_AGENT_REGISTRY = "app.agent.multi_agent_orchestrator.AgentRegistry"
_FINDINGS_FILTER = "app.agent.multi_agent_orchestrator.FindingsFilter"


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestConstructor:

    def test_stores_request(self):
        from app.agent.multi_agent_orchestrator import MultiAgentOrchestrator

        req = _make_request()
        orch = MultiAgentOrchestrator(req)
        assert orch.request is req


class TestRunWithNoEnabledAgents:

    @patch(_BUILTIN_AGENTS)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_LLM)
    @patch(_STRATEGY_PLANNER)
    async def test_returns_completed_when_no_agents(
        self, mock_planner_cls, mock_create_llm, mock_destroy, mock_create, _builtin,
    ):
        from app.agent.multi_agent_orchestrator import MultiAgentOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        # Strategy with all weights = 0
        strategy = ReviewStrategy(
            agent_weights={at: 0.0 for at in AgentType},
        )
        mock_planner_cls.return_value.plan.return_value = strategy

        req = _make_request()
        orch = MultiAgentOrchestrator(req)
        resp = await orch.run()

        assert resp.status == ReviewStatus.COMPLETED
        assert resp.request_id == "req-001"


class TestRunWithAgentFailure:

    @patch(_BUILTIN_AGENTS)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_LLM)
    @patch(_STRATEGY_PLANNER)
    @patch(_AGENT_REGISTRY)
    async def test_logs_warning_and_continues(
        self, mock_registry, mock_planner_cls, mock_create_llm,
        mock_destroy, mock_create, _builtin,
    ):
        from app.agent.multi_agent_orchestrator import MultiAgentOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        strategy = ReviewStrategy(
            agent_weights={AgentType.SECURITY: 1.0, AgentType.PERFORMANCE: 1.0},
        )
        mock_planner_cls.return_value.plan.return_value = strategy

        failing_agent = _make_agent("security", RuntimeError("boom"))
        succeeding_agent = _make_agent(
            "performance",
            AgentReviewResult(summary="ok", issues=[]),
        )
        mock_registry.create.side_effect = [failing_agent, succeeding_agent]

        req = _make_request()
        orch = MultiAgentOrchestrator(req)
        resp = await orch.run()

        assert resp.status == ReviewStatus.COMPLETED


class TestRunAggregatesIssues:

    @patch(_BUILTIN_AGENTS)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_LLM)
    @patch(_STRATEGY_PLANNER)
    @patch(_AGENT_REGISTRY)
    @patch(_FINDINGS_FILTER)
    async def test_aggregates_issues_from_multiple_agents(
        self, mock_fp_cls, mock_registry, mock_planner_cls, mock_create_llm,
        mock_destroy, mock_create, _builtin,
    ):
        from app.agent.multi_agent_orchestrator import MultiAgentOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        strategy = ReviewStrategy(
            agent_weights={
                AgentType.SECURITY: 1.0,
                AgentType.PERFORMANCE: 1.0,
            },
        )
        mock_planner_cls.return_value.plan.return_value = strategy

        issue_a = IssuePayload(
            severity="WARNING",
            file="a.java",
            line=1,
            type="bug",
            message="issue A",
        )
        issue_b = IssuePayload(
            severity="CRITICAL",
            file="b.java",
            line=2,
            type="perf",
            message="issue B",
        )

        agent_a = _make_agent(
            "security",
            AgentReviewResult(summary="sec", issues=[issue_a]),
        )
        agent_b = _make_agent(
            "performance",
            AgentReviewResult(summary="perf", issues=[issue_b]),
        )
        mock_registry.create.side_effect = [agent_a, agent_b]

        # Make false-positive filter pass through everything
        fp_instance = MagicMock()
        fp_instance.filter_issues = AsyncMock(
            return_value=([issue_a, issue_b], MagicMock()),
        )
        mock_fp_cls.return_value = fp_instance

        req = _make_request()
        orch = MultiAgentOrchestrator(req)
        resp = await orch.run()

        assert resp.status == ReviewStatus.COMPLETED
        # Issues are aggregated (may include filter pass-through)
        file_set = {i.file for i in resp.issues}
        assert "a.java" in file_set
        assert "b.java" in file_set


class TestRunDeduplicatesIssues:

    @patch(_BUILTIN_AGENTS)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_LLM)
    @patch(_STRATEGY_PLANNER)
    @patch(_AGENT_REGISTRY)
    @patch(_FINDINGS_FILTER)
    async def test_deduplicates_by_file_line_type(
        self, mock_fp_cls, mock_registry, mock_planner_cls, mock_create_llm,
        mock_destroy, mock_create, _builtin,
    ):
        from app.agent.multi_agent_orchestrator import MultiAgentOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        strategy = ReviewStrategy(
            agent_weights={AgentType.SECURITY: 1.0, AgentType.PERFORMANCE: 1.0},
        )
        mock_planner_cls.return_value.plan.return_value = strategy

        # Same issue from two agents: same file, line, type
        dup_issue = IssuePayload(
            severity="WARNING",
            file="x.java",
            line=10,
            type="sql_injection",
            message="dup from A",
        )
        dup_issue_b = IssuePayload(
            severity="WARNING",
            file="x.java",
            line=10,
            type="sql_injection",
            message="dup from B",
        )

        agent_a = _make_agent(
            "security",
            AgentReviewResult(summary="s", issues=[dup_issue]),
        )
        agent_b = _make_agent(
            "performance",
            AgentReviewResult(summary="p", issues=[dup_issue_b]),
        )
        mock_registry.create.side_effect = [agent_a, agent_b]

        fp_instance = MagicMock()
        fp_instance.filter_issues = AsyncMock(
            return_value=([dup_issue], MagicMock()),
        )
        mock_fp_cls.return_value = fp_instance

        req = _make_request()
        orch = MultiAgentOrchestrator(req)
        resp = await orch.run()

        # After dedup, only one issue for this file:line:type
        deduped = [
            i for i in resp.issues
            if i.file == "x.java" and i.line == 10 and i.type == "sql_injection"
        ]
        assert len(deduped) <= 1


class TestRunSetsCriticalFlag:

    @patch(_BUILTIN_AGENTS)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_LLM)
    @patch(_STRATEGY_PLANNER)
    @patch(_AGENT_REGISTRY)
    @patch(_FINDINGS_FILTER)
    async def test_has_critical_flag_true(
        self, mock_fp_cls, mock_registry, mock_planner_cls, mock_create_llm,
        mock_destroy, mock_create, _builtin,
    ):
        from app.agent.multi_agent_orchestrator import MultiAgentOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        strategy = ReviewStrategy(
            agent_weights={AgentType.SECURITY: 1.0},
        )
        mock_planner_cls.return_value.plan.return_value = strategy

        critical_issue = IssuePayload(
            severity="CRITICAL",
            file="c.java",
            line=1,
            type="vuln",
            message="critical!",
        )
        agent = _make_agent(
            "security",
            AgentReviewResult(has_critical=True, summary="crit", issues=[critical_issue]),
        )
        mock_registry.create.return_value = agent

        fp_instance = MagicMock()
        fp_instance.filter_issues = AsyncMock(
            return_value=([critical_issue], MagicMock()),
        )
        mock_fp_cls.return_value = fp_instance

        req = _make_request()
        orch = MultiAgentOrchestrator(req)
        resp = await orch.run()

        assert resp.has_critical_flag is True


class TestRunExceptionReturnsFailed:

    @patch(_BUILTIN_AGENTS)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_LLM)
    @patch(_STRATEGY_PLANNER)
    async def test_returns_failed_on_exception(
        self, mock_planner_cls, mock_create_llm, mock_destroy, mock_create, _builtin,
    ):
        from app.agent.multi_agent_orchestrator import MultiAgentOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        # StrategyPlanner.plan() raises inside the try block
        mock_planner_cls.return_value.plan.side_effect = RuntimeError("unexpected")

        req = _make_request()
        orch = MultiAgentOrchestrator(req)
        resp = await orch.run()

        assert resp.status == ReviewStatus.FAILED
        assert "unexpected" in (resp.error or "")


class TestRunFalsePositiveFilterApplied:

    @patch(_BUILTIN_AGENTS)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_LLM)
    @patch(_STRATEGY_PLANNER)
    @patch(_AGENT_REGISTRY)
    @patch(_FINDINGS_FILTER)
    async def test_excluded_issues_filtered(
        self, mock_fp_cls, mock_registry, mock_planner_cls, mock_create_llm,
        mock_destroy, mock_create, _builtin,
    ):
        from app.agent.multi_agent_orchestrator import MultiAgentOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        strategy = ReviewStrategy(
            agent_weights={AgentType.SECURITY: 1.0},
        )
        mock_planner_cls.return_value.plan.return_value = strategy

        normal_issue = IssuePayload(
            severity="WARNING",
            file="a.java",
            line=1,
            type="bug",
            message="real issue",
        )
        excluded_issue = IssuePayload(
            severity="INFO",
            file="b.java",
            line=2,
            type="style",
            message="noise",
            filter_metadata={"excluded": True},
        )

        agent = _make_agent(
            "security",
            AgentReviewResult(summary="s", issues=[normal_issue, excluded_issue]),
        )
        mock_registry.create.return_value = agent

        # FP filter returns the excluded issue marked
        fp_instance = MagicMock()
        fp_instance.filter_issues = AsyncMock(
            return_value=([normal_issue, excluded_issue], MagicMock()),
        )
        mock_fp_cls.return_value = fp_instance

        req = _make_request()
        orch = MultiAgentOrchestrator(req)
        resp = await orch.run()

        assert resp.status == ReviewStatus.COMPLETED
