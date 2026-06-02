"""Tests for P0 fixes: chunking and aggregation line mapping."""

from __future__ import annotations

from diffguard_agent.models.schemas import (
    DiffEntry,
    IssuePayload,
)
from diffguard_agent.config import settings


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
        from diffguard_agent.agent.pipeline_orchestrator import _chunk_diff_entries

        entries = self._make_entries(5)
        chunks = _chunk_diff_entries(entries)
        assert len(chunks) == 1
        assert len(chunks[0]) == 5

    def test_exact_limit_no_split(self):
        from diffguard_agent.agent.pipeline_orchestrator import _chunk_diff_entries, MAX_FILES_PER_CHUNK

        entries = self._make_entries(MAX_FILES_PER_CHUNK)
        chunks = _chunk_diff_entries(entries)
        assert len(chunks) == 1

    def test_over_limit_splits(self):
        from diffguard_agent.agent.pipeline_orchestrator import _chunk_diff_entries, MAX_FILES_PER_CHUNK

        entries = self._make_entries(MAX_FILES_PER_CHUNK + 1)
        chunks = _chunk_diff_entries(entries)
        assert len(chunks) == 2
        # Packing is token-aware and may reorder entries; only assert totals and limits.
        assert sum(len(c) for c in chunks) == MAX_FILES_PER_CHUNK + 1
        assert all(len(c) <= MAX_FILES_PER_CHUNK for c in chunks)

    def test_large_diff_splits_by_chars(self):
        from diffguard_agent.agent.pipeline_orchestrator import (
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
        from diffguard_agent.agent.pipeline_orchestrator import _chunk_diff_entries

        entries = self._make_entries(25)
        chunks = _chunk_diff_entries(entries)
        total = sum(len(c) for c in chunks)
        assert total == 25

    def test_empty_input(self):
        from diffguard_agent.agent.pipeline_orchestrator import _chunk_diff_entries

        chunks = _chunk_diff_entries([])
        assert chunks == [[]]

    def test_small_file_count_can_still_chunk_by_token_budget(self):
        from diffguard_agent.agent.pipeline_orchestrator import _chunk_diff_entries, MAX_TOKENS_PER_CHUNK

        # 5 files only, but token_count forces split
        entries = [
            DiffEntry(file_path=f"f{i}.py", content="x" * 200, token_count=MAX_TOKENS_PER_CHUNK // 2 + 200)
            for i in range(5)
        ]
        chunks = _chunk_diff_entries(entries)
        assert len(chunks) > 1

    def test_oversized_single_file_is_split_by_hunks(self):
        from diffguard_agent.agent.pipeline_orchestrator import _chunk_diff_entries, MAX_CHARS_PER_CHUNK

        big_hunks = []
        for i in range(6):
            big_hunks.append(
                f"@@ -{i+1},1 +{i+1},1 @@\n" + "+" + ("x" * (MAX_CHARS_PER_CHUNK // 4)) + "\n"
            )
        content = (
            "diff --git a/big.py b/big.py\n"
            "--- a/big.py\n"
            "+++ b/big.py\n"
            + "".join(big_hunks)
        )
        entries = [DiffEntry(file_path="big.py", content=content)]
        chunks = _chunk_diff_entries(entries)
        # Should split a single oversized diff into multiple packed entries/chunks
        assert len(chunks) >= 2
        assert sum(len(c) for c in chunks) >= 2

    def test_chunk_builder_preserves_original_file_path(self):
        from diffguard_agent.agent.pipeline_orchestrator import _chunk_diff_entries

        content = (
            "diff --git a/a.py b/a.py\n"
            "--- a/a.py\n"
            "+++ b/a.py\n"
            "@@ -1,1 +1,1 @@\n"
            "+x\n"
        )
        entries = [DiffEntry(file_path="a.py", content=content)]
        chunks = _chunk_diff_entries(entries)
        assert chunks[0][0].file_path == "a.py"

    def test_respects_configured_max_files(self):
        from diffguard_agent.agent.pipeline_orchestrator import _chunk_diff_entries

        original = settings.CHUNK_MAX_FILES
        settings.CHUNK_MAX_FILES = 2
        try:
            entries = self._make_entries(5, chars_per_entry=50)
            chunks = _chunk_diff_entries(entries)
            assert len(chunks) >= 3
            assert all(len(c) <= 2 for c in chunks)
        finally:
            settings.CHUNK_MAX_FILES = original

    def test_split_large_hunk_rewrites_header_offsets(self):
        from diffguard_agent.agent.pipeline_orchestrator import _split_large_hunk

        hunk = (
            "@@ -10,8 +20,8 @@ optional suffix\n"
            " line_a\n"
            "-old_1\n"
            "+new_1\n"
            " line_b\n"
            "-old_2\n"
            "+new_2\n"
            " line_c\n"
            "+new_3\n"
        )
        # Small limit forces multiple pieces.
        pieces = _split_large_hunk(hunk, max_chars=55)
        assert len(pieces) >= 2
        # Headers should be rewritten and shifted, not all identical to original.
        assert pieces[0].startswith("@@ -10,")
        assert any(p.startswith("@@ -13,") or p.startswith("@@ -14,") for p in pieces[1:])

    def test_split_hunk_preserves_line_mapping(self):
        from diffguard_agent.agent.pipeline_orchestrator import _split_large_hunk
        from diffguard_agent.agent.diff_parser import DiffLineMapper

        hunk = (
            "@@ -1,4 +1,5 @@\n"
            " keep1\n"
            "-old1\n"
            "+new1\n"
            " keep2\n"
            "-old2\n"
            "+new2\n"
            "+new3\n"
        )
        pieces = _split_large_hunk(hunk, max_chars=45)
        rebuilt = (
            "diff --git a/a.py b/a.py\n"
            "--- a/a.py\n"
            "+++ b/a.py\n"
            + "".join(pieces)
        )
        mapper = DiffLineMapper(rebuilt)
        # Locate line numbers of added lines in rebuilt diff and ensure mapping exists.
        for idx, line in enumerate(rebuilt.splitlines(), start=1):
            if line.startswith("+new"):
                mapped = mapper.diff_line_to_file_line("a.py", idx)
                assert mapped is not None


class TestDeduplicateIssues:
    """Tests for _deduplicate_issues in pipeline_orchestrator."""

    def test_dedup_same_file_type_message(self):
        from diffguard_agent.agent.pipeline_orchestrator import _deduplicate_issues

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
        from diffguard_agent.agent.pipeline_orchestrator import _deduplicate_issues

        issues = [
            IssuePayload(file="a.py", type="sql_injection", message="SQL injection"),
            IssuePayload(file="a.py", type="xss", message="XSS vulnerability"),
        ]
        result = _deduplicate_issues(issues)
        assert len(result) == 2

    def test_empty_input(self):
        from diffguard_agent.agent.pipeline_orchestrator import _deduplicate_issues

        assert _deduplicate_issues([]) == []

    def test_same_message_different_lines_not_deduped(self):
        from diffguard_agent.agent.pipeline_orchestrator import _deduplicate_issues

        issues = [
            IssuePayload(
                file="a.py",
                line=10,
                type="sql_injection",
                message="Potential SQL injection found",
                severity="WARNING",
            ),
            IssuePayload(
                file="a.py",
                line=20,
                type="sql_injection",
                message="Potential SQL injection found",
                severity="WARNING",
            ),
        ]
        result = _deduplicate_issues(issues)
        assert len(result) == 2

    def test_message_normalization_keeps_single_issue(self):
        from diffguard_agent.agent.pipeline_orchestrator import _deduplicate_issues

        issues = [
            IssuePayload(
                file="a.py",
                line=10,
                type="xss",
                message="Reflected   XSS vulnerability",
                severity="WARNING",
            ),
            IssuePayload(
                file="a.py",
                line=10,
                type="xss",
                message="reflected xss vulnerability",
                severity="CRITICAL",
            ),
        ]
        result = _deduplicate_issues(issues)
        assert len(result) == 1
        assert result[0].severity == "CRITICAL"


# ---------------------------------------------------------------------------
# P0-3: Aggregation line mapping tests
# ---------------------------------------------------------------------------

class TestAggregationLineMapping:
    """Tests for _map_issue_line_numbers in aggregation stage."""

    def test_line_mapping_applied(self):
        from diffguard_agent.agent.pipeline.stages.aggregation import _map_issue_line_numbers
        from diffguard_agent.agent.diff_parser import DiffLineMapper

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
        from diffguard_agent.agent.pipeline.stages.aggregation import _map_issue_line_numbers
        from diffguard_agent.agent.diff_parser import DiffLineMapper

        mapper = DiffLineMapper("")
        issue = IssuePayload(file="", line=5, type="bug", message="test")
        result = _map_issue_line_numbers(issue, mapper, "")
        # No file path → no mapping, line stays 5
        assert result.line == 5

    def test_line_mapping_none_line(self):
        from diffguard_agent.agent.pipeline.stages.aggregation import _map_issue_line_numbers
        from diffguard_agent.agent.diff_parser import DiffLineMapper

        mapper = DiffLineMapper("")
        issue = IssuePayload(file="svc.py", line=None, type="bug", message="test")
        result = _map_issue_line_numbers(issue, mapper, "")
        assert result.line is None


# ---------------------------------------------------------------------------
# P0-1: Verify reviewer fallback fix (integration check)
# ---------------------------------------------------------------------------

class TestReviewerFallbackFix:
    """Ensure _parse_fallback uses correct variable name."""

    def test_no_undefined_variable_in_fallback(self):
        """Check that _parse_fallback does not reference 'structured_llm'."""
        import inspect
        from diffguard_agent.agent.pipeline.stages.reviewer import ReviewerStage

        stage = ReviewerStage()
        source = inspect.getsource(stage._parse_fallback)

        # The bug was: structured_llm (undefined) instead of structured
        assert "structured_llm" not in source, (
            "Bug still present: _parse_fallback references undefined 'structured_llm'"
        )
        assert "structured.ainvoke" in source, (
            "Expected 'structured.ainvoke' call not found"
        )
