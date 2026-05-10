"""Tests for app.agent.pipeline.stages - base, summary, reviewer, aggregation, false_positive_filter."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.agent.pipeline.stages.base import PipelineContext, PipelineStage
from app.models.schemas import IssuePayload


# ---------------------------------------------------------------------------
# PipelineStage abstract base
# ---------------------------------------------------------------------------


class TestPipelineStageAbstract:

    def test_cannot_instantiate(self):
        with pytest.raises(TypeError):
            PipelineStage()


# ---------------------------------------------------------------------------
# Stage name properties
# ---------------------------------------------------------------------------


class TestSummaryStageName:

    def test_name_returns_summary(self):
        from app.agent.pipeline.stages.summary import SummaryStage

        stage = SummaryStage()
        assert stage.name == "summary"


class TestReviewerStageName:

    def test_name_returns_review(self):
        from app.agent.pipeline.stages.reviewer import ReviewerStage

        stage = ReviewerStage()
        assert stage.name == "review"


class TestAggregationStageName:

    def test_name_returns_aggregation(self):
        from app.agent.pipeline.stages.aggregation import AggregationStage

        stage = AggregationStage()
        assert stage.name == "aggregation"


class TestFalsePositiveFilterStageName:

    def test_name_returns_false_positive_filter(self):
        from app.agent.pipeline.stages.false_positive_filter import FalsePositiveFilterStage

        stage = FalsePositiveFilterStage()
        assert stage.name == "false_positive_filter"


# ---------------------------------------------------------------------------
# PipelineContext
# ---------------------------------------------------------------------------


class TestPipelineContextDefaults:

    def test_defaults(self):
        ctx = PipelineContext()
        assert ctx.diff_text == ""
        assert ctx.llm is None
        assert ctx.tool_client is None
        assert ctx.summary == ""
        assert ctx.changed_files == []
        assert ctx.change_types == []
        assert ctx.estimated_risk_level == 3
        assert ctx.review_results == {}
        assert ctx.final_issues == []
        assert ctx.final_summary == ""
        assert ctx.has_critical is False
        assert ctx.highlights == []
        assert ctx.test_suggestions == []

    def test_filter_stats_field_exists(self):
        ctx = PipelineContext()
        assert hasattr(ctx, "filter_stats")
        assert ctx.filter_stats is None


# ---------------------------------------------------------------------------
# FalsePositiveFilterStage.execute
# ---------------------------------------------------------------------------


class TestFalsePositiveFilterStageExecute:

    @patch("app.agent.pipeline.stages.false_positive_filter.FindingsFilter")
    async def test_filters_excluded_issues(self, mock_fp_cls):
        from app.agent.pipeline.stages.false_positive_filter import FalsePositiveFilterStage

        normal_issue = IssuePayload(
            severity="WARNING",
            file="src/Main.java",
            line=10,
            type="sql_injection",
            message="Potential SQL injection",
            suggestion="Use PreparedStatement",
        )
        excluded_issue = IssuePayload(
            severity="INFO",
            file="docs/readme.md",
            line=1,
            type="style",
            message="Consider adding more comments",
            suggestion="Add comments",
            filter_metadata={"excluded": True, "reason": "Finding in documentation file"},
        )

        mock_stats = MagicMock()
        mock_stats.total_input = 2
        mock_stats.excluded_by_hard_rules = 1

        fp_instance = MagicMock()
        fp_instance.filter_issues = AsyncMock(
            return_value=([normal_issue, excluded_issue], mock_stats),
        )
        mock_fp_cls.return_value = fp_instance

        ctx = PipelineContext(
            diff_text="some diff",
            final_issues=[normal_issue, excluded_issue],
        )

        stage = FalsePositiveFilterStage()
        result_ctx = await stage.execute(ctx)

        assert len(result_ctx.final_issues) == 2
        # One should be excluded
        excluded = [i for i in result_ctx.final_issues if i.filter_metadata.get("excluded")]
        assert len(excluded) >= 1

    @patch("app.agent.pipeline.stages.false_positive_filter.FindingsFilter")
    async def test_updates_filter_stats(self, mock_fp_cls):
        from app.agent.pipeline.stages.false_positive_filter import FalsePositiveFilterStage

        mock_stats = MagicMock()
        mock_stats.total_input = 1
        mock_stats.excluded_by_hard_rules = 0

        issue = IssuePayload(
            severity="WARNING",
            file="a.java",
            line=1,
            type="bug",
            message="issue",
        )

        fp_instance = MagicMock()
        fp_instance.filter_issues = AsyncMock(
            return_value=([issue], mock_stats),
        )
        mock_fp_cls.return_value = fp_instance

        ctx = PipelineContext(diff_text="diff", final_issues=[issue])

        stage = FalsePositiveFilterStage()
        result_ctx = await stage.execute(ctx)

        assert result_ctx.filter_stats is mock_stats

    @patch("app.agent.pipeline.stages.false_positive_filter.FindingsFilter")
    async def test_false_positive_patterns_excluded(self, mock_fp_cls):
        """Verify that issues with known false positive patterns are filtered."""
        from app.agent.pipeline.stages.false_positive_filter import FalsePositiveFilterStage

        # This issue matches the "Overly generic suggestion" rule
        generic_issue = IssuePayload(
            severity="INFO",
            file="src/Service.java",
            line=5,
            type="quality",
            message="Code could be improved",
            suggestion="Follow best practices",
        )

        # This is a real issue
        real_issue = IssuePayload(
            severity="CRITICAL",
            file="src/Controller.java",
            line=20,
            type="sql_injection",
            message="Unparameterized SQL query detected",
            suggestion="Use parameterized query",
        )

        # Mark the generic one as excluded by the filter
        generic_excluded = IssuePayload(
            severity="INFO",
            file="src/Service.java",
            line=5,
            type="quality",
            message="Code could be improved",
            suggestion="Follow best practices",
            confidence=0.0,
            filter_metadata={
                "excluded": True,
                "reason": "Overly generic suggestion without actionable specifics",
                "stage": "hard_rules",
            },
        )

        mock_stats = MagicMock()
        mock_stats.total_input = 2
        mock_stats.excluded_by_hard_rules = 1

        fp_instance = MagicMock()
        fp_instance.filter_issues = AsyncMock(
            return_value=([real_issue, generic_excluded], mock_stats),
        )
        mock_fp_cls.return_value = fp_instance

        ctx = PipelineContext(
            diff_text="some diff",
            final_issues=[generic_issue, real_issue],
        )

        stage = FalsePositiveFilterStage()
        result_ctx = await stage.execute(ctx)

        excluded = [
            i for i in result_ctx.final_issues
            if i.filter_metadata.get("excluded")
        ]
        assert len(excluded) == 1
        assert excluded[0].message == "Code could be improved"


# ---------------------------------------------------------------------------
# AggregationStage.execute
# ---------------------------------------------------------------------------


class TestAggregationStageExecute:

    @patch("app.agent.pipeline.stages.aggregation.load_prompt")
    async def test_merges_review_results(self, mock_load_prompt):
        from app.agent.pipeline.stages.aggregation import AggregationStage

        # Mock prompts
        mock_load_prompt.side_effect = [
            "aggregation system prompt",
            "aggregation user prompt with {{summary}} and {{reviewer_results}}",
        ]

        # Mock structured LLM output
        mock_aggregated = MagicMock()
        mock_aggregated.issues = [
            IssuePayload(
                severity="CRITICAL",
                file="a.java",
                line=10,
                type="vuln",
                message="merged critical",
            ),
        ]
        mock_aggregated.summary = "Merged: 1 critical issue"
        mock_aggregated.has_critical = True
        mock_aggregated.highlights = ["a.java:10"]
        mock_aggregated.test_suggestions = ["Test edge cases"]

        mock_llm = MagicMock()
        mock_structured = MagicMock()
        mock_structured.ainvoke = AsyncMock(return_value=mock_aggregated)
        mock_llm.with_structured_output.return_value = mock_structured

        ctx = PipelineContext(
            diff_text="diff",
            llm=mock_llm,
            summary="2 reviewers ran",
            review_results={
                "security": '{"summary": "sec review", "issues": []}',
                "logic": '{"summary": "logic review", "issues": []}',
            },
        )

        stage = AggregationStage()
        result_ctx = await stage.execute(ctx)

        assert len(result_ctx.final_issues) == 1
        assert result_ctx.final_issues[0].severity == "CRITICAL"
        assert result_ctx.has_critical is True
        assert result_ctx.final_summary == "Merged: 1 critical issue"
        assert result_ctx.highlights == ["a.java:10"]
        assert result_ctx.test_suggestions == ["Test edge cases"]
