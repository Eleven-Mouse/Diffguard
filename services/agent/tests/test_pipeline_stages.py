"""Tests for app.agent.pipeline.stages - base, summary, reviewer, aggregation, false_positive_filter."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from diffguard_agent.agent.pipeline.stages.base import (
    AggregationOutput,
    PipelineContext,
    PipelineInput,
    PipelineStage,
    ReviewOutput,
    SummaryOutput,
)
from diffguard_agent.models.schemas import IssuePayload


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
        from diffguard_agent.agent.pipeline.stages.summary import SummaryStage

        stage = SummaryStage()
        assert stage.name == "summary"


class TestReviewerStageName:

    def test_name_returns_review(self):
        from diffguard_agent.agent.pipeline.stages.reviewer import ReviewerStage

        stage = ReviewerStage()
        assert stage.name == "review"


class TestReviewerLoopGuard:

    async def test_repeated_same_tool_call_triggers_guard(self):
        from diffguard_agent.agent.pipeline.stages.reviewer import (
            _ReActLoopDetectedError,
            _ReActLoopGuard,
            _wrap_tool_with_loop_guard,
        )

        async def repeated_tool(query: str) -> str:
            return f"ok:{query}"

        repeated_tool.__name__ = "semantic_search"  # type: ignore[attr-defined]
        wrapped = _wrap_tool_with_loop_guard(repeated_tool, _ReActLoopGuard(max_consecutive_repeats=3))

        assert await wrapped("auth token") == "ok:auth token"
        assert await wrapped("auth token") == "ok:auth token"
        with pytest.raises(_ReActLoopDetectedError):
            await wrapped("auth token")

    async def test_two_step_cycle_triggers_guard(self):
        from diffguard_agent.agent.pipeline.stages.reviewer import (
            _ReActLoopDetectedError,
            _ReActLoopGuard,
            _wrap_tool_with_loop_guard,
        )

        async def search_tool(query: str) -> str:
            return "search-ok"

        async def related_tool(query: str) -> str:
            return "related-ok"

        search_tool.__name__ = "semantic_search"  # type: ignore[attr-defined]
        related_tool.__name__ = "get_related_files"  # type: ignore[attr-defined]
        guard = _ReActLoopGuard(max_consecutive_repeats=10)
        wrapped_search = _wrap_tool_with_loop_guard(search_tool, guard)
        wrapped_related = _wrap_tool_with_loop_guard(related_tool, guard)

        await wrapped_search("login")
        await wrapped_related("login")
        await wrapped_search("login")
        await wrapped_related("login")
        await wrapped_search("login")
        with pytest.raises(_ReActLoopDetectedError):
            await wrapped_related("login")

    def test_loop_guard_error_matcher_accepts_nested_cause(self):
        from diffguard_agent.agent.pipeline.stages.reviewer import _is_loop_guard_error

        inner = RuntimeError("[loop_guard] repeated identical tool action")
        outer = RuntimeError("tool execution failed")
        outer.__cause__ = inner

        assert _is_loop_guard_error(outer) is True


class TestAggregationStageName:

    def test_name_returns_aggregation(self):
        from diffguard_agent.agent.pipeline.stages.aggregation import AggregationStage

        stage = AggregationStage()
        assert stage.name == "aggregation"


class TestFalsePositiveFilterStageName:

    def test_name_returns_false_positive_filter(self):
        from diffguard_agent.agent.pipeline.stages.fp_filter_stage import FalsePositiveFilterStage

        stage = FalsePositiveFilterStage()
        assert stage.name == "false_positive_filter"


# ---------------------------------------------------------------------------
# PipelineContext
# ---------------------------------------------------------------------------


class TestPipelineContextDefaults:

    def test_defaults(self):
        ctx = PipelineContext()
        # input sub-object
        assert ctx.input.diff_text == ""
        assert ctx.input.llm is None
        assert ctx.input.tool_client is None
        # summary sub-object
        assert ctx.summary.summary == ""
        assert ctx.summary.changed_files == []
        assert ctx.summary.change_types == []
        assert ctx.summary.estimated_risk_level == 3
        # review sub-object
        assert ctx.review.review_results == {}
        # aggregation sub-object
        assert ctx.aggregation.final_issues == []
        assert ctx.aggregation.final_summary == ""
        assert ctx.aggregation.has_critical is False
        assert ctx.aggregation.highlights == []
        assert ctx.aggregation.test_suggestions == []

    def test_filter_stats_field_exists(self):
        ctx = PipelineContext()
        assert hasattr(ctx.aggregation, "filter_stats")
        assert ctx.aggregation.filter_stats is None


# ---------------------------------------------------------------------------
# FalsePositiveFilterStage.execute
# ---------------------------------------------------------------------------


class TestFalsePositiveFilterStageExecute:

    @patch("diffguard_agent.agent.pipeline.stages.fp_filter_stage.FindingsFilter")
    async def test_filters_excluded_issues(self, mock_fp_cls):
        from diffguard_agent.agent.pipeline.stages.fp_filter_stage import FalsePositiveFilterStage

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
            input=PipelineInput(diff_text="some diff"),
            aggregation=AggregationOutput(final_issues=[normal_issue, excluded_issue]),
        )

        stage = FalsePositiveFilterStage()
        result_ctx = await stage.execute(ctx)

        assert len(result_ctx.aggregation.final_issues) == 2
        # One should be excluded
        excluded = [i for i in result_ctx.aggregation.final_issues if i.filter_metadata.get("excluded")]
        assert len(excluded) >= 1

    @patch("diffguard_agent.agent.pipeline.stages.fp_filter_stage.FindingsFilter")
    async def test_updates_filter_stats(self, mock_fp_cls):
        from diffguard_agent.agent.pipeline.stages.fp_filter_stage import FalsePositiveFilterStage

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

        ctx = PipelineContext(
            input=PipelineInput(diff_text="diff"),
            aggregation=AggregationOutput(final_issues=[issue]),
        )

        stage = FalsePositiveFilterStage()
        result_ctx = await stage.execute(ctx)

        assert result_ctx.aggregation.filter_stats is mock_stats

    @patch("diffguard_agent.agent.pipeline.stages.fp_filter_stage.FindingsFilter")
    async def test_false_positive_patterns_excluded(self, mock_fp_cls):
        """Verify that issues with known false positive patterns are filtered."""
        from diffguard_agent.agent.pipeline.stages.fp_filter_stage import FalsePositiveFilterStage

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
            input=PipelineInput(diff_text="some diff"),
            aggregation=AggregationOutput(final_issues=[generic_issue, real_issue]),
        )

        stage = FalsePositiveFilterStage()
        result_ctx = await stage.execute(ctx)

        excluded = [
            i for i in result_ctx.aggregation.final_issues
            if i.filter_metadata.get("excluded")
        ]
        assert len(excluded) == 1
        assert excluded[0].message == "Code could be improved"

    @patch("diffguard_agent.agent.pipeline.stages.fp_filter_stage.FindingsFilter")
    async def test_recomputes_has_critical_after_exclusion(self, mock_fp_cls):
        from diffguard_agent.agent.pipeline.stages.fp_filter_stage import FalsePositiveFilterStage

        excluded_critical = IssuePayload(
            severity="CRITICAL",
            file="src/Auth.java",
            line=42,
            type="auth_bypass",
            message="Authentication bypass",
            suggestion="Add permission check",
            confidence=0.0,
            filter_metadata={
                "excluded": True,
                "reason": "false positive",
                "stage": "hard_rules",
            },
        )
        visible_warning = IssuePayload(
            severity="WARNING",
            file="src/User.java",
            line=12,
            type="logic",
            message="Potential null access",
            suggestion="Add guard",
            confidence=0.9,
            filter_metadata={},
        )

        mock_stats = MagicMock()
        mock_stats.total_input = 2
        mock_stats.excluded_by_hard_rules = 1

        fp_instance = MagicMock()
        fp_instance.filter_issues = AsyncMock(
            return_value=([excluded_critical, visible_warning], mock_stats),
        )
        mock_fp_cls.return_value = fp_instance

        ctx = PipelineContext(
            input=PipelineInput(diff_text="some diff"),
            aggregation=AggregationOutput(
                final_issues=[excluded_critical, visible_warning],
                has_critical=True,
            ),
        )

        stage = FalsePositiveFilterStage()
        result_ctx = await stage.execute(ctx)

        assert result_ctx.aggregation.has_critical is False


# ---------------------------------------------------------------------------
# AggregationStage.execute
# ---------------------------------------------------------------------------


class TestAggregationStageExecute:

    @patch("diffguard_agent.agent.pipeline.stages.aggregation.load_prompt")
    async def test_merges_review_results(self, mock_load_prompt):
        from diffguard_agent.agent.pipeline.stages.aggregation import AggregationStage

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
            input=PipelineInput(diff_text="diff", llm=mock_llm),
            summary=SummaryOutput(summary="2 reviewers ran"),
            review=ReviewOutput(review_results={
                "security": '{"summary": "sec review", "issues": []}',
                "logic": '{"summary": "logic review", "issues": []}',
            }),
        )

        stage = AggregationStage()
        result_ctx = await stage.execute(ctx)

        assert len(result_ctx.aggregation.final_issues) == 1
        assert result_ctx.aggregation.final_issues[0].severity == "CRITICAL"
        assert result_ctx.aggregation.has_critical is True
        assert result_ctx.aggregation.final_summary == "Merged: 1 critical issue"
        assert result_ctx.aggregation.highlights == ["a.java:10"]
        assert result_ctx.aggregation.test_suggestions == ["Test edge cases"]

    @patch("diffguard_agent.agent.pipeline.stages.aggregation.load_prompt")
    async def test_reviewer_section_preserves_source_reviewer(self, mock_load_prompt):
        from diffguard_agent.agent.pipeline.stages.aggregation import _build_reviewer_section

        mock_load_prompt.side_effect = [
            "aggregation system prompt",
            "aggregation user prompt with {{summary}} and {{reviewer_results}}",
        ]

        review_results = {
            "logic": (
                '{"summary":"logic review",'
                '"issues":[{"severity":"WARNING","file":"a.java","line":12,'
                '"type":"logic_check","message":"auth token may be null","suggestion":"guard"}]}'
            ),
            "security": '{"summary":"security review","issues":[]}',
        }

        section, total = _build_reviewer_section(review_results)

        assert total == 1
        assert "【logic】" in section
        assert '"issue_count": 1' in section

    @patch("diffguard_agent.agent.pipeline.stages.aggregation.load_prompt")
    async def test_none_aggregated_result_falls_back_without_crash(self, mock_load_prompt):
        from diffguard_agent.agent.pipeline.stages.aggregation import AggregationStage

        mock_load_prompt.side_effect = [
            "aggregation system prompt",
            "aggregation user prompt with {{summary}} and {{reviewer_results}}",
        ]

        mock_llm = MagicMock()
        mock_structured = MagicMock()
        mock_structured.ainvoke = AsyncMock(return_value=None)
        mock_llm.with_structured_output.return_value = mock_structured

        pre_issue = IssuePayload(
            severity="WARNING",
            file="src/a.py",
            line=10,
            type="logic",
            message="pre-aggregated issue",
            suggestion="fix",
        )

        ctx = PipelineContext(
            input=PipelineInput(diff_text="diff", llm=mock_llm),
            summary=SummaryOutput(summary="summary"),
            review=ReviewOutput(review_results={
                "security": '{"summary": "sec review", "issues": []}',
                "static_rules": [pre_issue],
            }),
        )

        stage = AggregationStage()
        result_ctx = await stage.execute(ctx)

        assert len(result_ctx.aggregation.final_issues) == 1
        assert result_ctx.aggregation.final_issues[0].message == "pre-aggregated issue"
        assert result_ctx.aggregation.has_critical is False
        assert "审查完成" in result_ctx.aggregation.final_summary
