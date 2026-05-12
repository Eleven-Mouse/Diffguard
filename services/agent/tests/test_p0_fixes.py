"""Tests for P0 fixes: chunking, HMAC verification, aggregation line mapping."""

from __future__ import annotations

import hashlib
import hmac
import json
import time
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.models.schemas import (
    DiffEntry,
    IssuePayload,
    LlmConfig,
    ReviewMode,
    ReviewRequest,
    ReviewStatus,
)


# ---------------------------------------------------------------------------
# P0-2: Chunking tests
# ---------------------------------------------------------------------------

class TestDiffChunking:
    """Tests for _chunk_diff_entries in pipeline_orchestrator."""

    def _make_entries(self, n: int, chars_per_entry: int = 100) -> list[DiffEntry]:
        return [
            DiffEntry(file_path=f"file_{i}.py", content="x" * chars_per_entry)
            for i in range(n)
        ]

    def test_small_diff_no_chunking(self):
        from app.agent.pipeline_orchestrator import _chunk_diff_entries, MAX_FILES_PER_CHUNK

        entries = self._make_entries(5)
        chunks = _chunk_diff_entries(entries)
        assert len(chunks) == 1
        assert len(chunks[0]) == 5

    def test_exact_limit_no_split(self):
        from app.agent.pipeline_orchestrator import _chunk_diff_entries, MAX_FILES_PER_CHUNK

        entries = self._make_entries(MAX_FILES_PER_CHUNK)
        chunks = _chunk_diff_entries(entries)
        assert len(chunks) == 1

    def test_over_limit_splits(self):
        from app.agent.pipeline_orchestrator import _chunk_diff_entries, MAX_FILES_PER_CHUNK

        entries = self._make_entries(MAX_FILES_PER_CHUNK + 1)
        chunks = _chunk_diff_entries(entries)
        assert len(chunks) == 2
        assert len(chunks[0]) == MAX_FILES_PER_CHUNK
        assert len(chunks[1]) == 1

    def test_large_diff_splits_by_chars(self):
        from app.agent.pipeline_orchestrator import (
            _chunk_diff_entries,
            MAX_CHARS_PER_CHUNK,
            MAX_FILES_PER_CHUNK,
        )

        # Create files that individually fit within file count but together
        # exceed MAX_CHARS_PER_CHUNK.  Use more than MAX_FILES_PER_CHUNK files
        # to force splitting, each large enough to test char limits.
        entries = [
            DiffEntry(file_path=f"big_{i}.py", content="x" * (MAX_CHARS_PER_CHUNK - 1))
            for i in range(MAX_FILES_PER_CHUNK + 1)
        ]
        chunks = _chunk_diff_entries(entries)
        assert len(chunks) >= 2

    def test_all_entries_preserved(self):
        from app.agent.pipeline_orchestrator import _chunk_diff_entries

        entries = self._make_entries(25)
        chunks = _chunk_diff_entries(entries)
        total = sum(len(c) for c in chunks)
        assert total == 25

    def test_empty_input(self):
        from app.agent.pipeline_orchestrator import _chunk_diff_entries

        chunks = _chunk_diff_entries([])
        assert chunks == [[]]


class TestDeduplicateIssues:
    """Tests for _deduplicate_issues in pipeline_orchestrator."""

    def test_dedup_same_file_type_message(self):
        from app.agent.pipeline_orchestrator import _deduplicate_issues

        issues = [
            IssuePayload(file="a.py", type="sql_injection", message="SQL injection found",
                         severity="WARNING"),
            IssuePayload(file="a.py", type="sql_injection", message="SQL injection found",
                         severity="CRITICAL"),
        ]
        result = _deduplicate_issues(issues)
        assert len(result) == 1
        assert result[0].severity == "CRITICAL"  # higher severity wins

    def test_different_issues_kept(self):
        from app.agent.pipeline_orchestrator import _deduplicate_issues

        issues = [
            IssuePayload(file="a.py", type="sql_injection", message="SQL injection"),
            IssuePayload(file="a.py", type="xss", message="XSS vulnerability"),
        ]
        result = _deduplicate_issues(issues)
        assert len(result) == 2

    def test_empty_input(self):
        from app.agent.pipeline_orchestrator import _deduplicate_issues

        assert _deduplicate_issues([]) == []


# ---------------------------------------------------------------------------
# P0-3: Aggregation line mapping tests
# ---------------------------------------------------------------------------

class TestAggregationLineMapping:
    """Tests for _map_issue_line_numbers in aggregation stage."""

    def test_line_mapping_applied(self):
        from app.agent.pipeline.stages.aggregation import _map_issue_line_numbers
        from app.agent.diff_parser import DiffLineMapper

        diff = (
            "diff --git a/svc.py b/svc.py\n"
            "--- a/svc.py\n"
            "+++ b/svc.py\n"
            "@@ -1,3 +1,4 @@\n"
            " line1\n"
            "+added_line\n"
            " line2\n"
        )
        mapper = DiffLineMapper(diff)
        issue = IssuePayload(file="svc.py", line=6, type="bug", message="issue on added line")
        result = _map_issue_line_numbers(issue, mapper, diff)
        # line 6 in diff = "+added_line" → new_file_line = 2
        assert result.line == 2

    def test_line_mapping_no_file(self):
        from app.agent.pipeline.stages.aggregation import _map_issue_line_numbers
        from app.agent.diff_parser import DiffLineMapper

        mapper = DiffLineMapper("")
        issue = IssuePayload(file="", line=5, type="bug", message="test")
        result = _map_issue_line_numbers(issue, mapper, "")
        # No file path → no mapping, line stays 5
        assert result.line == 5

    def test_line_mapping_none_line(self):
        from app.agent.pipeline.stages.aggregation import _map_issue_line_numbers
        from app.agent.diff_parser import DiffLineMapper

        mapper = DiffLineMapper("")
        issue = IssuePayload(file="svc.py", line=None, type="bug", message="test")
        result = _map_issue_line_numbers(issue, mapper, "")
        assert result.line is None


# ---------------------------------------------------------------------------
# P0-4: HMAC signature verification tests
# ---------------------------------------------------------------------------

class TestHMACVerification:
    """Tests for webhook HMAC signature verification in main.py."""

    def _make_sig(self, body: bytes, secret: str) -> str:
        return "sha256=" + hmac.new(
            secret.encode(), body, hashlib.sha256
        ).hexdigest()

    @pytest.mark.asyncio
    async def test_no_secret_skips_verification(self):
        """When WEBHOOK_HMAC_SECRET is not set, verification is skipped."""
        from app.main import _verify_webhook_signature

        mock_req = MagicMock()
        mock_req.headers = {}
        mock_req.body = AsyncMock(return_value=b"test")

        with patch("app.main.settings") as mock_settings:
            mock_settings.WEBHOOK_HMAC_SECRET = None
            result = await _verify_webhook_signature.__wrapped__(b"test", mock_req) \
                if hasattr(_verify_webhook_signature, "__wrapped__") else None
            # Function is sync, not async — let's call it directly
        from app.main import _verify_webhook_signature
        mock_req2 = MagicMock()
        mock_req2.headers = {}

        with patch("app.main.settings") as mock_settings:
            mock_settings.WEBHOOK_HMAC_SECRET = None
            result = _verify_webhook_signature(b"test", mock_req2)
            assert result is None  # No error = verification skipped

    def test_missing_signature_header_rejected(self):
        from app.main import _verify_webhook_signature

        mock_req = MagicMock()
        mock_req.headers = {}  # No X-DiffGuard-Signature

        with patch("app.main.settings") as mock_settings:
            mock_settings.WEBHOOK_HMAC_SECRET = "test-secret"
            result = _verify_webhook_signature(b"body", mock_req)
            assert result is not None
            assert result.status_code == 401

    def test_invalid_signature_rejected(self):
        from app.main import _verify_webhook_signature

        mock_req = MagicMock()
        mock_req.headers = {"X-DiffGuard-Signature": "sha256=invalid"}

        with patch("app.main.settings") as mock_settings:
            mock_settings.WEBHOOK_HMAC_SECRET = "test-secret"
            result = _verify_webhook_signature(b"body", mock_req)
            assert result is not None
            assert result.status_code == 401

    def test_valid_signature_accepted(self):
        from app.main import _verify_webhook_signature

        secret = "test-secret"
        body = b'{"repo_full_name": "test/repo", "pr_number": 1}'
        sig = self._make_sig(body, secret)

        mock_req = MagicMock()
        mock_req.headers = {"X-DiffGuard-Signature": sig}

        with patch("app.main.settings") as mock_settings:
            mock_settings.WEBHOOK_HMAC_SECRET = secret
            result = _verify_webhook_signature(body, mock_req)
            assert result is None  # No error = accepted

    def test_expired_timestamp_rejected(self):
        from app.main import _verify_webhook_signature

        secret = "test-secret"
        body = b"body"
        sig = self._make_sig(body, secret)

        mock_req = MagicMock()
        mock_req.headers = {
            "X-DiffGuard-Signature": sig,
            "X-DiffGuard-Timestamp": str(int(time.time()) - 600),  # 10 min ago
        }

        with patch("app.main.settings") as mock_settings:
            mock_settings.WEBHOOK_HMAC_SECRET = secret
            result = _verify_webhook_signature(body, mock_req)
            assert result is not None
            assert result.status_code == 401


# ---------------------------------------------------------------------------
# P0-1: Verify reviewer fallback fix (integration check)
# ---------------------------------------------------------------------------

class TestReviewerFallbackFix:
    """Ensure _parse_fallback uses correct variable name."""

    def test_no_undefined_variable_in_fallback(self):
        """Check that _parse_fallback does not reference 'structured_llm'."""
        import inspect
        from app.agent.pipeline.stages.reviewer import ReviewerStage

        stage = ReviewerStage()
        source = inspect.getsource(stage._parse_fallback)

        # The bug was: structured_llm (undefined) instead of structured
        assert "structured_llm" not in source, (
            "Bug still present: _parse_fallback references undefined 'structured_llm'"
        )
        assert "structured.ainvoke" in source, (
            "Expected 'structured.ainvoke' call not found"
        )
