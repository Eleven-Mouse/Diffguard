"""Tests for app.agent.base - AgentReviewResult dataclass and ReviewAgent ABC."""

import json

import pytest

from app.agent.base import AgentReviewResult, ReviewAgent
from app.models.schemas import IssuePayload


# ---------------------------------------------------------------------------
# AgentReviewResult
# ---------------------------------------------------------------------------


class TestAgentReviewResultDefaults:
    """Default values of the dataclass."""

    def test_defaults(self):
        result = AgentReviewResult()
        assert result.has_critical is False
        assert result.summary == ""
        assert result.issues == []
        assert result.highlights == []
        assert result.confidence == 1.0


class TestAgentReviewResultWithIssues:

    def test_with_issues(self):
        issues = [
            IssuePayload(severity="WARNING", file="a.java", message="msg"),
        ]
        result = AgentReviewResult(
            has_critical=False,
            summary="Found 1 issue",
            issues=issues,
        )
        assert len(result.issues) == 1
        assert result.issues[0].severity == "WARNING"
        assert result.summary == "Found 1 issue"


class TestAgentReviewResultJson:

    def test_model_dump_json_produces_valid_json(self):
        result = AgentReviewResult(
            has_critical=False,
            summary="ok",
            issues=[],
        )
        raw = result.model_dump_json()
        parsed = json.loads(raw)
        assert parsed["has_critical"] is False
        assert parsed["summary"] == "ok"
        assert parsed["issues"] == []
        assert parsed["confidence"] == 1.0

    def test_round_trip_through_json(self):
        issues = [
            IssuePayload(
                severity="CRITICAL",
                file="x.java",
                line=10,
                type="bug",
                message="bad",
                suggestion="fix it",
                confidence=0.9,
            ),
        ]
        original = AgentReviewResult(
            has_critical=True,
            summary="critical found",
            issues=issues,
            highlights=["line 10"],
            confidence=0.9,
        )
        raw = original.model_dump_json()
        parsed = json.loads(raw)

        # Reconstruct from the JSON
        restored = AgentReviewResult(
            has_critical=parsed["has_critical"],
            summary=parsed["summary"],
            issues=[IssuePayload(**i) for i in parsed["issues"]],
            highlights=parsed["highlights"],
            confidence=parsed["confidence"],
        )
        assert restored.has_critical == original.has_critical
        assert restored.summary == original.summary
        assert len(restored.issues) == 1
        assert restored.issues[0].file == "x.java"
        assert restored.confidence == 0.9


class TestAgentReviewResultConfidenceDefault:

    def test_confidence_default_is_one(self):
        result = AgentReviewResult()
        assert result.confidence == 1.0


class TestAgentReviewResultHasCritical:

    def test_with_has_critical_true(self):
        result = AgentReviewResult(has_critical=True)
        assert result.has_critical is True


# ---------------------------------------------------------------------------
# ReviewAgent ABC
# ---------------------------------------------------------------------------


def _make_concrete_agent():
    """Return a concrete subclass that implements all abstract members."""

    class ConcreteAgent(ReviewAgent):
        @property
        def name(self) -> str:
            return "concrete"

        @property
        def description(self) -> str:
            return "A concrete test agent."

        async def review(
            self,
            llm,
            diff_text,
            tool_client,
            focus_areas=None,
            additional_rules=None,
            max_iterations=8,
        ):
            return AgentReviewResult()

    return ConcreteAgent


class TestReviewAgentAbstract:

    def test_cannot_instantiate_directly(self):
        with pytest.raises(TypeError):
            ReviewAgent()

    def test_concrete_subclass_can_instantiate(self):
        cls = _make_concrete_agent()
        agent = cls()
        assert agent.name == "concrete"
        assert agent.description == "A concrete test agent."

    def test_name_is_abstract(self):
        """A subclass that omits 'name' cannot be instantiated."""

        class IncompleteAgent(ReviewAgent):
            @property
            def description(self) -> str:
                return "desc"

            async def review(self, llm, diff_text, tool_client, **kwargs):
                return AgentReviewResult()

        with pytest.raises(TypeError):
            IncompleteAgent()

    def test_description_is_abstract(self):
        """A subclass that omits 'description' cannot be instantiated."""

        class IncompleteAgent(ReviewAgent):
            @property
            def name(self) -> str:
                return "x"

            async def review(self, llm, diff_text, tool_client, **kwargs):
                return AgentReviewResult()

        with pytest.raises(TypeError):
            IncompleteAgent()


class TestReviewAgentDefaultWeight:

    def test_default_weight(self):
        cls = _make_concrete_agent()
        agent = cls()
        assert agent.default_weight == 1.0
