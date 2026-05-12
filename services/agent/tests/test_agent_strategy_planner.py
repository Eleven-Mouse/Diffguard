"""Tests for app.agent.strategy_planner - enums, dataclasses, profiling, and planning."""

import pytest

from app.agent.strategy_planner import (
    AgentType,
    DiffProfile,
    FileCategory,
    RiskLevel,
    ReviewStrategy,
    StrategyPlanner,
    profile,
)
from app.models.schemas import DiffEntry


# ---------------------------------------------------------------------------
# FileCategory enum
# ---------------------------------------------------------------------------


class TestFileCategory:

    def test_has_all_expected_values(self):
        expected = {
            "CONTROLLER", "SERVICE", "DAO", "MODEL",
            "CONFIG", "TEST", "UTILITY", "OTHER",
        }
        actual = {e.name for e in FileCategory}
        assert actual == expected

    def test_controller_value(self):
        assert FileCategory.CONTROLLER.value == "controller"

    def test_dao_value(self):
        assert FileCategory.DAO.value == "dao"


# ---------------------------------------------------------------------------
# RiskLevel enum
# ---------------------------------------------------------------------------


class TestRiskLevel:

    def test_has_all_expected_values(self):
        expected = {"LOW", "MEDIUM", "HIGH", "CRITICAL"}
        actual = {e.name for e in RiskLevel}
        assert actual == expected

    def test_values_are_lowercase(self):
        for rl in RiskLevel:
            assert rl.value == rl.value.lower()


# ---------------------------------------------------------------------------
# AgentType enum
# ---------------------------------------------------------------------------


class TestAgentType:

    def test_has_all_expected_values(self):
        expected = {"SECURITY", "PERFORMANCE", "ARCHITECTURE"}
        actual = {e.name for e in AgentType}
        assert actual == expected

    def test_security_value(self):
        assert AgentType.SECURITY.value == "security"


# ---------------------------------------------------------------------------
# DiffProfile defaults
# ---------------------------------------------------------------------------


class TestDiffProfileDefaults:

    def test_defaults(self):
        p = DiffProfile()
        assert p.total_files == 0
        assert p.total_lines == 0
        assert p.category_distribution == {}
        assert p.has_database_operations is False
        assert p.has_security_sensitive_code is False
        assert p.has_concurrency_code is False
        assert p.has_external_api_calls is False
        assert p.overall_risk == RiskLevel.LOW


# ---------------------------------------------------------------------------
# ReviewStrategy helpers
# ---------------------------------------------------------------------------


class TestReviewStrategyGetEnabledAgents:

    def test_filters_by_weight_gt_zero(self):
        strategy = ReviewStrategy(
            agent_weights={
                AgentType.SECURITY: 1.5,
                AgentType.PERFORMANCE: 0.0,
                AgentType.ARCHITECTURE: 0.8,
            },
        )
        enabled = strategy.get_enabled_agents()
        assert AgentType.SECURITY in enabled
        assert AgentType.ARCHITECTURE in enabled
        assert AgentType.PERFORMANCE not in enabled

    def test_all_enabled_when_all_positive(self):
        strategy = ReviewStrategy(
            agent_weights={
                AgentType.SECURITY: 1.0,
                AgentType.PERFORMANCE: 1.0,
                AgentType.ARCHITECTURE: 1.0,
            },
        )
        assert len(strategy.get_enabled_agents()) == 3

    def test_none_enabled_when_all_zero(self):
        strategy = ReviewStrategy(
            agent_weights={
                AgentType.SECURITY: 0.0,
                AgentType.PERFORMANCE: 0.0,
                AgentType.ARCHITECTURE: 0.0,
            },
        )
        assert strategy.get_enabled_agents() == []


class TestReviewStrategyGetEnabledAgentNames:

    def test_returns_string_names(self):
        strategy = ReviewStrategy(
            agent_weights={
                AgentType.SECURITY: 1.0,
                AgentType.PERFORMANCE: 0.0,
            },
        )
        names = strategy.get_enabled_agent_names()
        assert "security" in names
        assert "performance" not in names

    def test_empty_when_all_disabled(self):
        strategy = ReviewStrategy(
            agent_weights={
                AgentType.SECURITY: 0.0,
                AgentType.PERFORMANCE: 0.0,
                AgentType.ARCHITECTURE: 0.0,
            },
        )
        assert strategy.get_enabled_agent_names() == []


# ---------------------------------------------------------------------------
# profile() function
# ---------------------------------------------------------------------------


class TestProfileEmptyEntries:

    def test_returns_low_risk(self):
        p = profile([])
        assert p.overall_risk == RiskLevel.LOW
        assert p.total_files == 0

    def test_no_flags_set(self):
        p = profile([])
        assert p.has_database_operations is False
        assert p.has_security_sensitive_code is False
        assert p.has_concurrency_code is False
        assert p.has_external_api_calls is False


class TestProfileControllerFile:

    def test_classifies_controller(self):
        entries = [
            DiffEntry(
                file_path="src/UserController.java",
                content="public class UserController {}",
            ),
        ]
        p = profile(entries)
        assert FileCategory.CONTROLLER in p.category_distribution
        assert p.category_distribution[FileCategory.CONTROLLER] == 1

    def test_has_api_operations(self):
        entries = [
            DiffEntry(
                file_path="src/UserController.java",
                content="@GetMapping public void get() {}",
            ),
        ]
        p = profile(entries)
        assert p.has_external_api_calls is True


class TestProfileDatabasePatterns:

    def test_has_database_operations(self):
        entries = [
            DiffEntry(
                file_path="src/Repo.java",
                content="JdbcTemplate.queryForObject(sql)",
            ),
        ]
        p = profile(entries)
        assert p.has_database_operations is True


class TestProfileSecurityPatterns:

    def test_has_security_sensitive_code(self):
        entries = [
            DiffEntry(
                file_path="src/Auth.java",
                content="@PreAuthorize public void admin() {}",
            ),
        ]
        p = profile(entries)
        assert p.has_security_sensitive_code is True


class TestProfileDAOFile:

    def test_classifies_dao(self):
        entries = [
            DiffEntry(
                file_path="src/UserRepository.java",
                content="public interface UserRepository {}",
            ),
        ]
        p = profile(entries)
        assert FileCategory.DAO in p.category_distribution
        assert p.category_distribution[FileCategory.DAO] == 1


class TestProfileTestFile:

    def test_classifies_test(self):
        entries = [
            DiffEntry(
                file_path="src/UserServiceTest.java",
                content="class UserServiceTest {}",
            ),
        ]
        p = profile(entries)
        assert FileCategory.TEST in p.category_distribution
        assert p.category_distribution[FileCategory.TEST] == 1


# ---------------------------------------------------------------------------
# StrategyPlanner.plan()
# ---------------------------------------------------------------------------


class TestStrategyPlannerPlan:

    def test_returns_review_strategy(self):
        planner = StrategyPlanner()
        entries = [
            DiffEntry(
                file_path="src/Util.java",
                content="class Util {}",
            ),
        ]
        strategy = planner.plan(entries)
        assert isinstance(strategy, ReviewStrategy)

    def test_dao_files_increases_security_weight(self):
        planner = StrategyPlanner()
        entries = [
            DiffEntry(
                file_path="src/UserDao.java",
                content="JdbcTemplate.query(sql)",
            ),
        ]
        strategy = planner.plan(entries)
        # Built-in DAO override sets SECURITY=2.0
        assert strategy.agent_weights.get(AgentType.SECURITY, 0) > 1.5

    def test_plan_name_reflects_primary_category(self):
        planner = StrategyPlanner()
        entries = [
            DiffEntry(
                file_path="src/UserController.java",
                content="public class UserController {}",
            ),
        ]
        strategy = planner.plan(entries)
        assert "controller" in strategy.name
