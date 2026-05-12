"""Tests for app.models.schemas - Pydantic models and enums."""

import os
from unittest.mock import patch

import pytest
from pydantic import ValidationError

from app.models.schemas import (
    IssuePayload,
    ReviewResponse,
    ReviewStatus,
    ReviewMode,
    LlmConfig,
    DiffEntry,
    ToolResponse,
)


# ---------------------------------------------------------------------------
# IssuePayload
# ---------------------------------------------------------------------------


class TestIssuePayloadDefaults:
    """IssuePayload default values."""

    def test_defaults(self):
        issue = IssuePayload()
        assert issue.severity == "INFO"
        assert issue.file == ""
        assert issue.line is None
        assert issue.type == ""
        assert issue.message == ""
        assert issue.suggestion == ""
        assert issue.confidence == 1.0
        assert issue.filter_metadata == {}


class TestIssuePayloadAllFields:
    """IssuePayload with every field explicitly set."""

    def test_all_fields(self):
        issue = IssuePayload(
            severity="CRITICAL",
            file="src/secret.java",
            line=99,
            type="hardcoded_secret",
            message="Hardcoded API key",
            suggestion="Move to env var",
            confidence=0.85,
            filter_metadata={"rule": "SEC101"},
        )
        assert issue.severity == "CRITICAL"
        assert issue.file == "src/secret.java"
        assert issue.line == 99
        assert issue.type == "hardcoded_secret"
        assert issue.message == "Hardcoded API key"
        assert issue.suggestion == "Move to env var"
        assert issue.confidence == 0.85
        assert issue.filter_metadata == {"rule": "SEC101"}


class TestIssuePayloadConfidenceValidation:
    """Confidence must be in [0.0, 1.0]."""

    def test_confidence_zero_valid(self):
        issue = IssuePayload(confidence=0.0)
        assert issue.confidence == 0.0

    def test_confidence_one_valid(self):
        issue = IssuePayload(confidence=1.0)
        assert issue.confidence == 1.0

    def test_confidence_negative_invalid(self):
        with pytest.raises(ValidationError):
            IssuePayload(confidence=-0.1)

    def test_confidence_above_one_invalid(self):
        with pytest.raises(ValidationError):
            IssuePayload(confidence=1.1)


class TestIssuePayloadBackwardCompat:
    """Parsing JSON that omits newer fields should still work."""

    def test_parse_without_confidence_and_filter_metadata(self):
        data = {
            "severity": "WARNING",
            "file": "a.java",
            "line": 1,
            "type": "bug",
            "message": "msg",
            "suggestion": "fix",
        }
        issue = IssuePayload.model_validate(data)
        assert issue.confidence == 1.0
        assert issue.filter_metadata == {}


# ---------------------------------------------------------------------------
# ReviewResponse
# ---------------------------------------------------------------------------


class TestReviewResponseDefaults:

    def test_defaults(self):
        resp = ReviewResponse(request_id="r1")
        assert resp.request_id == "r1"
        assert resp.status == ReviewStatus.COMPLETED
        assert resp.has_critical_flag is False
        assert resp.issues == []
        assert resp.total_tokens_used == 0
        assert resp.review_duration_ms == 0
        assert resp.summary == ""
        assert resp.error is None


class TestReviewResponseAllFields:

    def test_with_all_fields(self, sample_issues):
        resp = ReviewResponse(
            request_id="r2",
            status=ReviewStatus.FAILED,
            has_critical_flag=True,
            issues=sample_issues,
            total_tokens_used=500,
            review_duration_ms=1200,
            summary="Review complete",
            error="Something went wrong",
        )
        assert resp.status == ReviewStatus.FAILED
        assert resp.has_critical_flag is True
        assert len(resp.issues) == 3
        assert resp.total_tokens_used == 500
        assert resp.error == "Something went wrong"


# ---------------------------------------------------------------------------
# Enums
# ---------------------------------------------------------------------------


class TestReviewStatusEnum:

    def test_values(self):
        assert ReviewStatus.COMPLETED == "completed"
        assert ReviewStatus.FAILED == "failed"

    def test_members(self):
        members = list(ReviewStatus)
        assert len(members) == 2


class TestReviewModeEnum:

    def test_values(self):
        assert ReviewMode.PIPELINE == "PIPELINE"
        assert ReviewMode.MULTI_AGENT == "MULTI_AGENT"

    def test_members(self):
        members = list(ReviewMode)
        assert len(members) == 2


# ---------------------------------------------------------------------------
# LlmConfig
# ---------------------------------------------------------------------------


class TestLlmConfigDefaults:

    def test_defaults(self):
        cfg = LlmConfig()
        assert cfg.provider == "openai"
        assert cfg.model == "gpt-4o"
        assert cfg.api_key == ""
        assert cfg.api_key_env == "DIFFGUARD_API_KEY"
        assert cfg.base_url is None
        assert cfg.max_tokens == 16384
        assert cfg.temperature == 0.3
        assert cfg.timeout_seconds == 300


class TestLlmConfigResolveApiKey:

    def test_resolve_from_env(self):
        with patch.dict(os.environ, {"MY_API_KEY": "sk-test-123"}):
            cfg = LlmConfig(api_key="", api_key_env="MY_API_KEY")
            assert cfg.api_key == "sk-test-123"

    def test_explicit_api_key_not_overwritten(self):
        with patch.dict(os.environ, {"DIFFGUARD_API_KEY": "from-env"}):
            cfg = LlmConfig(api_key="explicit-key")
            assert cfg.api_key == "explicit-key"


# ---------------------------------------------------------------------------
# DiffEntry
# ---------------------------------------------------------------------------


class TestDiffEntry:

    def test_construction(self):
        entry = DiffEntry(file_path="a.java", content="diff", token_count=7)
        assert entry.file_path == "a.java"
        assert entry.content == "diff"
        assert entry.token_count == 7

    def test_default_token_count(self):
        entry = DiffEntry(file_path="b.java", content="diff")
        assert entry.token_count == 0


# ---------------------------------------------------------------------------
# ToolResponse
# ---------------------------------------------------------------------------


class TestToolResponse:

    def test_success(self):
        resp = ToolResponse(success=True, result="file content here")
        assert resp.success is True
        assert resp.result == "file content here"
        assert resp.error is None

    def test_failure(self):
        resp = ToolResponse(success=False, error="not found")
        assert resp.success is False
        assert resp.result is None
        assert resp.error == "not found"
