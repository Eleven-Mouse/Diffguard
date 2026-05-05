"""AgentMemory - short-term shared context between agents."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.agent.base import AgentReviewResult


@dataclass
class AgentMemory:
    """Short-term memory shared across agents in a single review session.

    Enables cross-agent knowledge sharing so later agents can leverage
    findings from earlier ones (e.g., security agent discovers a SQL injection
    and the performance agent can reference it).
    """

    findings: list[str] = field(default_factory=list)
    completed_agents: list[str] = field(default_factory=list)
    shared_context: dict[str, Any] = field(default_factory=dict)

    def add_finding(self, agent_name: str, finding: str) -> None:
        self.findings.append(f"[{agent_name}] {finding}")

    def mark_completed(self, agent_name: str) -> None:
        if agent_name not in self.completed_agents:
            self.completed_agents.append(agent_name)

    def add_result(self, agent_name: str, result: AgentReviewResult) -> None:
        self.shared_context[agent_name] = {
            "summary": result.summary,
            "issue_count": len(result.issues),
            "has_critical": result.has_critical,
        }
        if result.has_critical:
            self.add_finding(agent_name, "CRITICAL issue detected")
        self.mark_completed(agent_name)

    def get_findings_for(self, agent_name: str) -> str:
        """Get findings from other agents (excluding self)."""
        other_findings = [
            f for f in self.findings
            if not f.startswith(f"[{agent_name}]")
        ]
        return "\n".join(other_findings) if other_findings else ""

    def get_summary_context(self) -> str:
        """Get a compact summary of all completed agents."""
        lines = []
        for name, ctx in self.shared_context.items():
            lines.append(
                f"- {name}: {ctx['issue_count']} issues"
                f"{' (CRITICAL)' if ctx['has_critical'] else ''}"
            )
        return "\n".join(lines) if lines else ""
