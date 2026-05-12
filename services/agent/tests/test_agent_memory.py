"""Tests for app.agent.memory - AgentMemory dataclass."""

from app.agent.base import AgentReviewResult
from app.agent.memory import AgentMemory
from app.models.schemas import IssuePayload


# ---------------------------------------------------------------------------
# Default state
# ---------------------------------------------------------------------------


class TestAgentMemoryDefaults:

    def test_default_empty_state(self):
        mem = AgentMemory()
        assert mem.findings == []
        assert mem.completed_agents == []
        assert mem.shared_context == {}


# ---------------------------------------------------------------------------
# add_finding
# ---------------------------------------------------------------------------


class TestAddFinding:

    def test_appends_with_agent_prefix(self):
        mem = AgentMemory()
        mem.add_finding("security", "SQL injection found")
        assert mem.findings == ["[security] SQL injection found"]

    def test_multiple_findings(self):
        mem = AgentMemory()
        mem.add_finding("security", "issue A")
        mem.add_finding("performance", "issue B")
        assert len(mem.findings) == 2
        assert mem.findings[0] == "[security] issue A"
        assert mem.findings[1] == "[performance] issue B"


# ---------------------------------------------------------------------------
# mark_completed
# ---------------------------------------------------------------------------


class TestMarkCompleted:

    def test_adds_to_list(self):
        mem = AgentMemory()
        mem.mark_completed("security")
        assert "security" in mem.completed_agents

    def test_no_duplicates(self):
        mem = AgentMemory()
        mem.mark_completed("security")
        mem.mark_completed("security")
        assert mem.completed_agents.count("security") == 1

    def test_multiple_agents(self):
        mem = AgentMemory()
        mem.mark_completed("security")
        mem.mark_completed("performance")
        assert mem.completed_agents == ["security", "performance"]


# ---------------------------------------------------------------------------
# add_result
# ---------------------------------------------------------------------------


class TestAddResult:

    def test_stores_summary_context(self):
        mem = AgentMemory()
        result = AgentReviewResult(
            has_critical=False,
            summary="2 warnings found",
            issues=[
                IssuePayload(severity="WARNING"),
                IssuePayload(severity="INFO"),
            ],
        )
        mem.add_result("security", result)
        assert "security" in mem.shared_context
        ctx = mem.shared_context["security"]
        assert ctx["summary"] == "2 warnings found"
        assert ctx["issue_count"] == 2
        assert ctx["has_critical"] is False

    def test_critical_adds_finding(self):
        mem = AgentMemory()
        result = AgentReviewResult(
            has_critical=True,
            summary="Critical!",
            issues=[IssuePayload(severity="CRITICAL")],
        )
        mem.add_result("security", result)
        # Should have auto-added a finding for the critical issue
        assert len(mem.findings) == 1
        assert "[security] CRITICAL issue detected" in mem.findings[0]
        # Should also have marked as completed
        assert "security" in mem.completed_agents


# ---------------------------------------------------------------------------
# get_findings_for
# ---------------------------------------------------------------------------


class TestGetFindingsFor:

    def test_excludes_self_findings(self):
        mem = AgentMemory()
        mem.add_finding("security", "SQL injection")
        mem.add_finding("performance", "Slow loop")
        result = mem.get_findings_for("security")
        assert "SQL injection" not in result
        assert "Slow loop" in result

    def test_returns_all_other_findings_joined(self):
        mem = AgentMemory()
        mem.add_finding("security", "finding A")
        mem.add_finding("performance", "finding B")
        result = mem.get_findings_for("architecture")
        lines = result.split("\n")
        assert len(lines) == 2

    def test_returns_empty_when_no_other_findings(self):
        mem = AgentMemory()
        mem.add_finding("security", "only finding")
        result = mem.get_findings_for("security")
        assert result == ""

    def test_empty_memory_returns_empty(self):
        mem = AgentMemory()
        assert mem.get_findings_for("security") == ""


# ---------------------------------------------------------------------------
# get_summary_context
# ---------------------------------------------------------------------------


class TestGetSummaryContext:

    def test_formats_summaries(self):
        mem = AgentMemory()
        mem.shared_context = {
            "security": {"issue_count": 3, "has_critical": False},
            "performance": {"issue_count": 1, "has_critical": True},
        }
        ctx = mem.get_summary_context()
        assert "- security: 3 issues" in ctx
        assert "- performance: 1 issues (CRITICAL)" in ctx

    def test_empty_when_no_results(self):
        mem = AgentMemory()
        assert mem.get_summary_context() == ""


# ---------------------------------------------------------------------------
# Accumulation
# ---------------------------------------------------------------------------


class TestAccumulation:

    def test_multiple_add_result_calls(self):
        mem = AgentMemory()
        mem.add_result("security", AgentReviewResult(summary="sec", issues=[]))
        mem.add_result("performance", AgentReviewResult(summary="perf", issues=[]))
        assert len(mem.shared_context) == 2
        assert mem.completed_agents == ["security", "performance"]

    def test_completed_agents_tracks_multiple(self):
        mem = AgentMemory()
        for name in ["security", "performance", "architecture"]:
            mem.add_result(name, AgentReviewResult(summary=name))
        assert set(mem.completed_agents) == {"security", "performance", "architecture"}
