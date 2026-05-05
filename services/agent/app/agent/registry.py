"""AgentRegistry - dynamic registration and discovery of ReviewAgents."""

from __future__ import annotations

import logging
from typing import Type

from app.agent.base import ReviewAgent

logger = logging.getLogger(__name__)


class AgentRegistry:
    """Central registry for ReviewAgent implementations.

    Usage::

        @AgentRegistry.register("security")
        class SecurityAgent(ReviewAgent):
            ...

    Then later::

        agent_cls = AgentRegistry.get("security")
        agent = agent_cls()
        result = await agent.review(llm, diff_text, tool_client)
    """

    _agents: dict[str, Type[ReviewAgent]] = {}

    @classmethod
    def register(cls, name: str | None = None):
        """Decorator: register a ReviewAgent subclass.

        Args:
            name: Override name. Defaults to the agent's ``name`` property.
        """
        def wrapper(agent_cls: Type[ReviewAgent]):
            key = name or agent_cls().name
            if key in cls._agents:
                logger.warning("Overwriting registered agent: %s", key)
            cls._agents[key] = agent_cls
            logger.debug("Registered agent: %s -> %s", key, agent_cls.__name__)
            return agent_cls
        return wrapper

    @classmethod
    def get(cls, name: str) -> Type[ReviewAgent] | None:
        """Look up a registered agent by name."""
        return cls._agents.get(name)

    @classmethod
    def all(cls) -> dict[str, Type[ReviewAgent]]:
        """Return a copy of all registered agents."""
        return dict(cls._agents)

    @classmethod
    def names(cls) -> list[str]:
        """Return all registered agent names."""
        return list(cls._agents.keys())

    @classmethod
    def create(cls, name: str) -> ReviewAgent:
        """Instantiate a registered agent by name."""
        agent_cls = cls._agents.get(name)
        if not agent_cls:
            raise ValueError(f"Unknown agent: {name}. Registered: {cls.names()}")
        return agent_cls()

    @classmethod
    def create_all_enabled(cls, weights: dict[str, float]) -> list[ReviewAgent]:
        """Create instances of all agents with weight > 0."""
        instances = []
        for name, weight in weights.items():
            if weight > 0 and name in cls._agents:
                instances.append(cls._agents[name]())
        return instances
