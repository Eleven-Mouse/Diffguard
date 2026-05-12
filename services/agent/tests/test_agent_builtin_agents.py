"""Tests for app.agent.builtin_agents - built-in agent registration and properties."""

import pytest

from app.agent.registry import AgentRegistry


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _clean_registry():
    """Snapshot and restore the class-level registry around each test."""
    saved = dict(AgentRegistry._agents)
    AgentRegistry._agents.clear()
    yield
    AgentRegistry._agents.clear()
    AgentRegistry._agents.update(saved)


@pytest.fixture(autouse=True)
def _import_builtins():
    """Import builtin_agents so the @register decorators fire."""
    import app.agent.builtin_agents  # noqa: F401


# ---------------------------------------------------------------------------
# Security agent tests
# ---------------------------------------------------------------------------


class TestSecurityAgentRegistration:

    def test_security_agent_is_registered(self):
        agent_cls = AgentRegistry.get("security")
        assert agent_cls is not None, "Security agent should be registered under 'security'"

    def test_security_agent_name_property(self):
        agent = AgentRegistry.create("security")
        assert agent.name == "security"

    def test_security_agent_has_description(self):
        agent = AgentRegistry.create("security")
        assert isinstance(agent.description, str)
        assert len(agent.description) > 0

    def test_security_agent_default_weight(self):
        agent = AgentRegistry.create("security")
        assert agent.default_weight == 1.2

    def test_security_agent_description_mentions_security(self):
        agent = AgentRegistry.create("security")
        desc_lower = agent.description.lower()
        assert "security" in desc_lower or "vulnerability" in desc_lower

    def test_security_agent_create_via_registry(self):
        from app.agent.base import ReviewAgent

        agent = AgentRegistry.create("security")
        assert isinstance(agent, ReviewAgent)


# ---------------------------------------------------------------------------
# Performance agent tests
# ---------------------------------------------------------------------------


class TestPerformanceAgentRegistration:

    def test_performance_agent_is_registered(self):
        agent_cls = AgentRegistry.get("performance")
        assert agent_cls is not None, "Performance agent should be registered under 'performance'"

    def test_performance_agent_name_property(self):
        agent = AgentRegistry.create("performance")
        assert agent.name == "performance"

    def test_performance_agent_has_description(self):
        agent = AgentRegistry.create("performance")
        assert isinstance(agent.description, str)
        assert len(agent.description) > 0

    def test_performance_agent_default_weight(self):
        agent = AgentRegistry.create("performance")
        assert agent.default_weight == 1.0


# ---------------------------------------------------------------------------
# Architecture agent tests
# ---------------------------------------------------------------------------


class TestArchitectureAgentRegistration:

    def test_architecture_agent_is_registered(self):
        agent_cls = AgentRegistry.get("architecture")
        assert agent_cls is not None, "Architecture agent should be registered under 'architecture'"

    def test_architecture_agent_name_property(self):
        agent = AgentRegistry.create("architecture")
        assert agent.name == "architecture"

    def test_architecture_agent_has_description(self):
        agent = AgentRegistry.create("architecture")
        assert isinstance(agent.description, str)
        assert len(agent.description) > 0

    def test_architecture_agent_default_weight(self):
        agent = AgentRegistry.create("architecture")
        assert agent.default_weight == 1.0


# ---------------------------------------------------------------------------
# Cross-agent consistency
# ---------------------------------------------------------------------------


class TestBuiltinAgentsCross:

    def test_all_three_agents_registered(self):
        names = set(AgentRegistry.names())
        assert names == {"security", "performance", "architecture"}

    def test_all_agents_are_subclasses_of_review_agent(self):
        from app.agent.base import ReviewAgent

        for name in AgentRegistry.names():
            agent = AgentRegistry.create(name)
            assert isinstance(agent, ReviewAgent), f"{name} is not a ReviewAgent subclass"

    def test_all_agent_names_match_registry_key(self):
        for name in AgentRegistry.names():
            agent = AgentRegistry.create(name)
            assert agent.name == name, f"Agent.name '{agent.name}' != registry key '{name}'"

    def test_all_agents_have_nonempty_description(self):
        for name in AgentRegistry.names():
            agent = AgentRegistry.create(name)
            assert len(agent.description) > 0, f"{name} has empty description"

    def test_all_agents_have_positive_default_weight(self):
        for name in AgentRegistry.names():
            agent = AgentRegistry.create(name)
            assert agent.default_weight > 0, f"{name} has non-positive default_weight"
