"""Tests for app.agent.pipeline_orchestrator - default pipeline, PipelineOrchestrator."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.agent.pipeline.stages.base import PipelineContext, PipelineStage
from app.models.schemas import (
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
_CREATE_TOOL_SESSION = "app.agent.pipeline_orchestrator.create_tool_session"
_DESTROY_TOOL_SESSION = "app.agent.pipeline_orchestrator.destroy_tool_session"
_CREATE_LLM = "app.agent.pipeline_orchestrator.create_llm"


# ---------------------------------------------------------------------------
# Tests: build_default_pipeline
# ---------------------------------------------------------------------------


class TestBuildDefaultPipeline:

    def test_returns_four_stages(self):
        from app.agent.pipeline_orchestrator import build_default_pipeline

        stages = build_default_pipeline()
        assert len(stages) == 4

    def test_stage_names(self):
        from app.agent.pipeline_orchestrator import build_default_pipeline

        stages = build_default_pipeline()
        names = [s.name for s in stages]
        assert names == ["summary", "review", "aggregation", "false_positive_filter"]


# ---------------------------------------------------------------------------
# Tests: PipelineOrchestrator constructor
# ---------------------------------------------------------------------------


class TestPipelineOrchestratorConstructor:

    def test_uses_provided_stages(self):
        from app.agent.pipeline_orchestrator import PipelineOrchestrator

        stage = _make_stage("custom")
        req = _make_request()
        orch = PipelineOrchestrator(req, stages=[stage])
        assert len(orch.stages) == 1
        assert orch.stages[0].name == "custom"

    def test_uses_default_pipeline_when_none(self):
        from app.agent.pipeline_orchestrator import PipelineOrchestrator

        req = _make_request()
        orch = PipelineOrchestrator(req)
        assert len(orch.stages) == 4


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
        from app.agent.pipeline_orchestrator import PipelineOrchestrator

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
        from app.agent.pipeline_orchestrator import PipelineOrchestrator

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
        from app.agent.pipeline_orchestrator import PipelineOrchestrator

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
        from app.agent.pipeline_orchestrator import PipelineOrchestrator

        mock_tool_client = MagicMock()
        mock_create.return_value = mock_tool_client
        mock_create_llm.return_value = MagicMock()

        req = _make_request()
        orch = PipelineOrchestrator(req, stages=[_make_stage("s")])
        await orch.run()

        mock_create.assert_awaited_once()
        mock_destroy.assert_awaited_once_with(mock_tool_client)
