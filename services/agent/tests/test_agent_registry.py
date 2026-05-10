"""Tests for app.agent.registry - AgentRegistry class-level registration."""

from typing import Any

import pytest

from app.agent.base import AgentReviewResult, ReviewAgent
from app.agent.registry import AgentRegistry


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


class _StubAgent(ReviewAgent):
    """Minimal concrete ReviewAgent for testing the registry."""

    @property
    def name(self) -> str:
        return "stub"

    @property
    def description(self) -> str:
        return "Stub agent for testing."

    async def review(
        self,
        llm: Any,
        diff_text: str,
        tool_client: Any,
        focus_areas: list[str] | None = None,
        additional_rules: list[str] | None = None,
        max_iterations: int = 8,
    ) -> AgentReviewResult:
        return AgentReviewResult()


class _AnotherAgent(ReviewAgent):
    """A second concrete agent with a different weight."""

    @property
    def name(self) -> str:
        return "another"

    @property
    def description(self) -> str:
        return "Another test agent."

    @property
    def default_weight(self) -> float:
        return 0.5

    async def review(
        self,
        llm: Any,
        diff_text: str,
        tool_client: Any,
        focus_areas: list[str] | None = None,
        additional_rules: list[str] | None = None,
        max_iterations: int = 8,
    ) -> AgentReviewResult:
        return AgentReviewResult()


@pytest.fixture(autouse=True)
def _clean_registry():
    """Ensure the class-level _agents dict is clean before and after each test."""
    saved = dict(AgentRegistry._agents)
    AgentRegistry._agents.clear()
    yield
    AgentRegistry._agents.clear()
    AgentRegistry._agents.update(saved)


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestRegister:

    def test_register_stores_agent_class(self):
        AgentRegistry._agents["stub"] = _StubAgent
        assert "stub" in AgentRegistry._agents
        assert AgentRegistry._agents["stub"] is _StubAgent

    def test_register_via_decorator(self):
        decorated = AgentRegistry.register("stub")(_StubAgent)
        assert decorated is _StubAgent
        assert AgentRegistry.get("stub") is _StubAgent


class TestGet:

    def test_get_returns_registered_class(self):
        AgentRegistry._agents["stub"] = _StubAgent
        assert AgentRegistry.get("stub") is _StubAgent

    def test_get_returns_none_for_unknown(self):
        assert AgentRegistry.get("nonexistent") is None


class TestCreate:

    def test_create_instantiates_registered_agent(self):
        AgentRegistry._agents["stub"] = _StubAgent
        agent = AgentRegistry.create("stub")
        assert isinstance(agent, _StubAgent)
        assert agent.name == "stub"

    def test_create_raises_for_unknown(self):
        with pytest.raises(ValueError, match="Unknown agent"):
            AgentRegistry.create("nonexistent")


class TestAll:

    def test_all_returns_copy(self):
        AgentRegistry._agents["stub"] = _StubAgent
        result = AgentRegistry.all()
        assert result == {"stub": _StubAgent}
        # Verify it is a copy, not the original dict
        result["extra"] = None
        assert "extra" not in AgentRegistry._agents


class TestNames:

    def test_names_returns_all_names(self):
        AgentRegistry._agents["stub"] = _StubAgent
        AgentRegistry._agents["another"] = _AnotherAgent
        names = AgentRegistry.names()
        assert set(names) == {"stub", "another"}


class TestCreateAllEnabled:

    def test_returns_agents_with_positive_weight(self):
        AgentRegistry._agents["stub"] = _StubAgent
        AgentRegistry._agents["another"] = _AnotherAgent
        weights = {"stub": 1.0, "another": 0.5, "disabled": 0.0}
        agents = AgentRegistry.create_all_enabled(weights)
        names = [a.name for a in agents]
        assert "stub" in names
        assert "another" in names
        assert "disabled" not in names

    def test_excludes_zero_weight(self):
        AgentRegistry._agents["stub"] = _StubAgent
        agents = AgentRegistry.create_all_enabled({"stub": 0.0})
        assert len(agents) == 0


class TestReRegistration:

    def test_overwrites_previous(self):
        AgentRegistry._agents["stub"] = _StubAgent
        AgentRegistry._agents["stub"] = _AnotherAgent
        assert AgentRegistry.get("stub") is _AnotherAgent


class TestEmptyRegistry:

    def test_get_on_empty(self):
        assert AgentRegistry.get("anything") is None

    def test_names_on_empty(self):
        assert AgentRegistry.names() == []

    def test_all_on_empty(self):
        assert AgentRegistry.all() == {}

    def test_create_all_enabled_on_empty(self):
        assert AgentRegistry.create_all_enabled({"x": 1.0}) == []
