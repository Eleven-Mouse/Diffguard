"""Tests for app.agent.pipeline_orchestrator - default pipeline, PipelineOrchestrator."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from diffguard_agent.agent.pipeline.stages.base import PipelineContext, PipelineStage
from diffguard_agent.config import settings
from diffguard_agent.models.schemas import (
    DiffEntry,
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
        request_id="req-pipe-001",
        mode=ReviewMode.PIPELINE,
        project_dir="/tmp/project",
        diff_entries=[
            DiffEntry(file_path="src/Main.java", content="class Main {}", token_count=5),
        ],
        llm_config=LlmConfig(provider="openai", model="gpt-4o"),
        review_config=ReviewConfigPayload(),
    )
    defaults.update(overrides)
    return ReviewRequest(**defaults)


def _make_stage(name: str, mutate_fn=None):
    """Create a mock PipelineStage with given name and optional context mutation."""
    stage = MagicMock(spec=PipelineStage)
    stage.name = name

    async def _execute(ctx):
        if mutate_fn:
            mutate_fn(ctx)
        return ctx

    stage.execute = _execute
    return stage


# ---------------------------------------------------------------------------
# Patch targets
# ---------------------------------------------------------------------------
_CREATE_TOOL_SESSION = "diffguard_agent.agent.pipeline_orchestrator.create_tool_session"
_DESTROY_TOOL_SESSION = "diffguard_agent.agent.pipeline_orchestrator.destroy_tool_session"
_CREATE_LLM = "diffguard_agent.agent.pipeline_orchestrator.create_llm"


# ---------------------------------------------------------------------------
# Tests: build_default_pipeline
# ---------------------------------------------------------------------------


class TestBuildDefaultPipeline:

    def test_returns_four_stages(self):
        from diffguard_agent.agent.pipeline_orchestrator import build_default_pipeline

        stages = build_default_pipeline()
        assert len(stages) == 4

    def test_stage_names(self):
        from diffguard_agent.agent.pipeline_orchestrator import build_default_pipeline

        stages = build_default_pipeline()
        names = [s.name for s in stages]
        assert names == ["summary", "review", "aggregation", "false_positive_filter"]

    def test_stage_names_without_fp_filter(self):
        from diffguard_agent.agent.pipeline_orchestrator import build_default_pipeline

        stages = build_default_pipeline(enable_fp_filter=False)
        names = [s.name for s in stages]
        assert names == ["summary", "review", "aggregation"]


# ---------------------------------------------------------------------------
# Tests: PipelineOrchestrator constructor
# ---------------------------------------------------------------------------


class TestPipelineOrchestratorConstructor:

    def test_uses_provided_stages(self):
        from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator

        stage = _make_stage("custom")
        req = _make_request()
        orch = PipelineOrchestrator(req, stages=[stage])
        assert len(orch.stages) == 1
        assert orch.stages[0].name == "custom"

    def test_uses_default_pipeline_when_none(self):
        from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator

        req = _make_request()
        orch = PipelineOrchestrator(req)
        assert len(orch.stages) == 4

    def test_can_disable_fp_filter_in_default_pipeline(self):
        from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator

        req = _make_request()
        orch = PipelineOrchestrator(req, enable_fp_filter=False)
        assert [s.name for s in orch.stages] == ["summary", "review", "aggregation"]


# ---------------------------------------------------------------------------
# Tests: PipelineOrchestrator.run()
# ---------------------------------------------------------------------------


class TestPipelineOrchestratorRun:

    @patch(_CREATE_LLM)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    async def test_executes_stages_sequentially(
        self, mock_create, mock_destroy, mock_create_llm,
    ):
        from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        execution_order = []

        def track(name):
            def mutate(ctx):
                execution_order.append(name)
            return mutate

        stages = [
            _make_stage("s1", track("s1")),
            _make_stage("s2", track("s2")),
            _make_stage("s3", track("s3")),
        ]

        req = _make_request()
        orch = PipelineOrchestrator(req, stages=stages)
        await orch.run()

        assert execution_order == ["s1", "s2", "s3"]

    @patch(_CREATE_LLM)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    async def test_returns_review_response_on_success(
        self, mock_create, mock_destroy, mock_create_llm,
    ):
        from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        req = _make_request()
        orch = PipelineOrchestrator(req, stages=[_make_stage("only")])
        resp = await orch.run()

        assert isinstance(resp, ReviewResponse)
        assert resp.status == ReviewStatus.COMPLETED
        assert resp.request_id == "req-pipe-001"

    @patch(_CREATE_LLM)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    async def test_returns_failed_on_exception(
        self, mock_create, mock_destroy, mock_create_llm,
    ):
        from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator

        mock_create_llm.return_value = MagicMock()

        # Fail inside the try block by having create_tool_session raise
        mock_create.side_effect = RuntimeError("tool session failed")

        req = _make_request()
        orch = PipelineOrchestrator(req, stages=[_make_stage("s")])
        resp = await orch.run()

        assert resp.status == ReviewStatus.FAILED
        assert "tool session failed" in (resp.error or "")

    @patch(_CREATE_LLM)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    async def test_creates_and_destroys_tool_session(
        self, mock_create, mock_destroy, mock_create_llm,
    ):
        from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator

        mock_tool_client = MagicMock()
        mock_create.return_value = mock_tool_client
        mock_create_llm.return_value = MagicMock()

        req = _make_request()
        orch = PipelineOrchestrator(req, stages=[_make_stage("s")])
        await orch.run()

        mock_create.assert_awaited_once()
        mock_destroy.assert_awaited_once_with(mock_tool_client)

    @patch(_CREATE_LLM)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    async def test_chunked_all_chunks_failed_returns_failed(
        self, mock_create, mock_destroy, mock_create_llm,
    ):
        from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        async def _boom(_ctx):
            raise RuntimeError("stage exploded")

        stage = MagicMock(spec=PipelineStage)
        stage.name = "boom"
        stage.execute = _boom

        req = _make_request(
            diff_entries=[
                DiffEntry(file_path=f"f{i}.py", content="x")
                for i in range(11)  # trigger chunking by file count
            ],
        )
        orch = PipelineOrchestrator(req, stages=[stage])
        resp = await orch.run()

        assert resp.status == ReviewStatus.FAILED
        assert "All" in (resp.error or "")
        assert "chunks failed" in (resp.error or "")

    @patch(_CREATE_LLM)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    async def test_chunked_partial_failure_marks_summary(
        self, mock_create, mock_destroy, mock_create_llm,
    ):
        from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        call_count = {"n": 0}

        async def _sometimes_fail(ctx):
            call_count["n"] += 1
            # First chunk succeeds, second chunk fails
            if call_count["n"] == 2:
                raise RuntimeError("chunk failed")
            ctx.aggregation.final_summary = "ok"
            return ctx

        stage = MagicMock(spec=PipelineStage)
        stage.name = "mixed"
        stage.execute = _sometimes_fail

        req = _make_request(
            diff_entries=[
                DiffEntry(file_path=f"f{i}.py", content="x")
                for i in range(11)  # two chunks
            ],
        )
        orch = PipelineOrchestrator(req, stages=[stage])
        resp = await orch.run()

        assert resp.status == ReviewStatus.COMPLETED
        assert "[partial review]" in (resp.summary or "")

    @patch(_CREATE_LLM)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    async def test_chunk_prompt_too_long_uses_fallback(
        self, mock_create, mock_destroy, mock_create_llm,
    ):
        from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        calls = {"n": 0}

        async def _stage_with_prompt_error(ctx):
            calls["n"] += 1
            if calls["n"] == 1:
                raise RuntimeError("Prompt is too long")
            ctx.aggregation.final_summary = "fallback ok"
            return ctx

        stage = MagicMock(spec=PipelineStage)
        stage.name = "recoverable"
        stage.execute = _stage_with_prompt_error

        req = _make_request(
            diff_entries=[
                DiffEntry(file_path=f"f{i}.py", content="x")
                for i in range(11)  # force chunk mode
            ],
        )
        orch = PipelineOrchestrator(req, stages=[stage])
        resp = await orch.run()

        assert resp.status == ReviewStatus.COMPLETED
        assert "[fallback-applied]" in (resp.summary or "")

    @patch(_CREATE_LLM)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    async def test_chunk_failure_ratio_over_threshold_returns_failed(
        self, mock_create, mock_destroy, mock_create_llm,
    ):
        from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        call_count = {"n": 0}

        async def _mostly_fail(_ctx):
            call_count["n"] += 1
            # For 3 chunks: fail first 2, succeed last -> failure ratio 2/3 > 0.5
            if call_count["n"] <= 2:
                raise RuntimeError("non-recoverable failure")
            return _ctx

        stage = MagicMock(spec=PipelineStage)
        stage.name = "ratio"
        stage.execute = _mostly_fail

        req = _make_request(
            diff_entries=[
                DiffEntry(file_path=f"f{i}.py", content="x")
                for i in range(21)  # 3 chunks by file limit
            ],
        )
        orch = PipelineOrchestrator(req, stages=[stage])
        resp = await orch.run()

        assert resp.status == ReviewStatus.FAILED
        assert "Too many chunk failures" in (resp.error or "")

    @patch(_CREATE_LLM)
    @patch(_DESTROY_TOOL_SESSION, new_callable=AsyncMock)
    @patch(_CREATE_TOOL_SESSION, new_callable=AsyncMock)
    async def test_chunk_failure_ratio_threshold_is_configurable(
        self, mock_create, mock_destroy, mock_create_llm,
    ):
        from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator

        mock_create.return_value = MagicMock()
        mock_create_llm.return_value = MagicMock()

        original_ratio = settings.CHUNK_MAX_FAILED_RATIO
        settings.CHUNK_MAX_FAILED_RATIO = 0.8
        try:
            call_count = {"n": 0}

            async def _mostly_fail(_ctx):
                call_count["n"] += 1
                # 3 chunks: fail 2, success 1 -> 0.67 (should pass if threshold=0.8)
                if call_count["n"] <= 2:
                    raise RuntimeError("non-recoverable failure")
                return _ctx

            stage = MagicMock(spec=PipelineStage)
            stage.name = "ratio_cfg"
            stage.execute = _mostly_fail

            req = _make_request(
                diff_entries=[
                    DiffEntry(file_path=f"f{i}.py", content="x")
                    for i in range(21)
                ],
            )
            orch = PipelineOrchestrator(req, stages=[stage])
            resp = await orch.run()

            assert resp.status == ReviewStatus.COMPLETED
            assert "[partial review]" in (resp.summary or "")
        finally:
            settings.CHUNK_MAX_FAILED_RATIO = original_ratio
