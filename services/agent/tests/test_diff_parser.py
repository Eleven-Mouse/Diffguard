"""Tests for app.agent.diff_parser - unified diff line number mapping."""

from __future__ import annotations

import pytest

from app.agent.diff_parser import DiffLineMapper, map_issue_line


# ---------------------------------------------------------------------------
# Sample unified diffs for testing
# ---------------------------------------------------------------------------

SAMPLE_DIFF = """\
diff --git a/src/Service.java b/src/Service.java
--- a/src/Service.java
+++ b/src/Service.java
@@ -10,5 +10,7 @@ public class Service {
     private String name;
+    private String email;
+    private Integer age;
     private String status;
-    private String legacy;
+    private Boolean active;
 }
"""

MULTI_HUNK_DIFF = """\
diff --git a/app.py b/app.py
--- a/app.py
+++ b/app.py
@@ -1,4 +1,5 @@
 import os
+import sys
 import logging

@@ -20,3 +21,4 @@ def main():
     run()
+    cleanup()
     return 0
"""

MULTI_FILE_DIFF = """\
diff --git a/a.py b/a.py
--- a/a.py
+++ b/a.py
@@ -1,3 +1,4 @@
 line1
+added_in_a
 line2
diff --git a/b.py b/b.py
--- a/b.py
+++ b/b.py
@@ -5,3 +5,4 @@
 line5
+added_in_b
 line6
"""


# ---------------------------------------------------------------------------
# Tests: DiffLineMapper
# ---------------------------------------------------------------------------


class TestDiffLineMapperBasic:

    def test_single_added_line(self):
        mapper = DiffLineMapper(SAMPLE_DIFF)
        # In SAMPLE_DIFF, the first '+' line ("private String email;") is at
        # diff abs line 6. The @@ header says +10, so new_start=10.
        # new_start=10 → context line maps to 10, first '+' maps to 11.
        # Actually let's count: line 1="diff --git", 2="--- a/...", 3="+++ b/...",
        # 4="@@ ...", 5="    private String name;", 6="+    private String email;"
        # After context "private String name;" at line 5 → new_file_line goes from 10 to 11
        # Then '+' at line 6 → maps to new_file_line=11
        actual = mapper.diff_line_to_file_line("src/Service.java", 6)
        assert actual == 11

    def test_second_added_line(self):
        mapper = DiffLineMapper(SAMPLE_DIFF)
        # line 7: "+    private Integer age;" → new_file_line=12
        actual = mapper.diff_line_to_file_line("src/Service.java", 7)
        assert actual == 12

    def test_deleted_line_not_mapped(self):
        mapper = DiffLineMapper(SAMPLE_DIFF)
        # line 9: "-    private String legacy;" → no mapping (deleted)
        actual = mapper.diff_line_to_file_line("src/Service.java", 9)
        assert actual is None

    def test_replacement_added_line(self):
        mapper = DiffLineMapper(SAMPLE_DIFF)
        # After deletion at line 9, the '+' at line 10 ("+    private Boolean active;")
        # new_file_line should be 14 (10 base + name=1 + email=2 + age=3 + status=4 + active=5... wait)
        # Let me recalculate:
        # @@ -10,5 +10,7 @@:  old starts at 10, new starts at 10
        # line 5: " " context → new_file_line = 10 → 11
        # line 6: "+" added  → new_file_line = 11 → 12
        # line 7: "+" added  → new_file_line = 12 → 13
        # line 8: " " context → new_file_line = 13 → 14
        # line 9: "-" deleted → no new file line advance
        # line 10: "+" added → new_file_line = 14
        actual = mapper.diff_line_to_file_line("src/Service.java", 10)
        assert actual == 14

    def test_unknown_file_returns_none(self):
        mapper = DiffLineMapper(SAMPLE_DIFF)
        assert mapper.diff_line_to_file_line("nonexistent.java", 5) is None

    def test_unknown_line_returns_none(self):
        mapper = DiffLineMapper(SAMPLE_DIFF)
        assert mapper.diff_line_to_file_line("src/Service.java", 999) is None


class TestDiffLineMapperMultiHunk:

    def test_first_hunk(self):
        mapper = DiffLineMapper(MULTI_HUNK_DIFF)
        # Line 1: "diff --git", 2: "--- a/...", 3: "+++ b/...", 4: "@@ ..."
        # Line 5: " " context → new=1, Line 6: "+import sys" → new=2
        actual = mapper.diff_line_to_file_line("app.py", 6)
        assert actual == 2

    def test_second_hunk(self):
        mapper = DiffLineMapper(MULTI_HUNK_DIFF)
        # Line 8: empty (context), 9: "@@ -20,3 +21,4 @@" → new_start=21
        # Line 10: "     run()" context → new=21, Line 11: "+    cleanup()" → new=22
        actual = mapper.diff_line_to_file_line("app.py", 11)
        assert actual == 22


class TestDiffLineMapperMultiFile:

    def test_file_a(self):
        mapper = DiffLineMapper(MULTI_FILE_DIFF)
        # Line 1: diff --git, 2: ---, 3: +++, 4: @@ ..., 5: " line1", 6: "+added_in_a"
        # "+added_in_a" is at diff line 6, maps to new_file_line=2
        actual = mapper.diff_line_to_file_line("a.py", 6)
        assert actual == 2

    def test_file_b(self):
        mapper = DiffLineMapper(MULTI_FILE_DIFF)
        # b.py: line 11: @@ → new_start=5, 12: " line5" context, 13: "+added_in_b" → new=6
        actual = mapper.diff_line_to_file_line("b.py", 13)
        assert actual == 6

    def test_file_path_in_diff(self):
        mapper = DiffLineMapper(MULTI_FILE_DIFF)
        assert mapper.file_path_in_diff("a.py") is True
        assert mapper.file_path_in_diff("c.py") is False


class TestMapIssueLineConvenience:

    def test_convenience_function(self):
        result = map_issue_line("src/Service.java", 6, SAMPLE_DIFF)
        assert result == 11

    def test_convenience_missing_file(self):
        result = map_issue_line("missing.java", 1, SAMPLE_DIFF)
        assert result is None


class TestDiffLineMapperEdgeCases:

    def test_empty_diff(self):
        mapper = DiffLineMapper("")
        assert mapper.file_path_in_diff("any.java") is False

    def test_diff_with_no_additions(self):
        diff = """\
diff --git a/deleted.py b/deleted.py
--- a/deleted.py
+++ b/deleted.py
@@ -1,3 +1,2 @@
-old_line
 keep
 keep2
"""
        mapper = DiffLineMapper(diff)
        # No '+' lines, so no mapping
        assert mapper.diff_line_to_file_line("deleted.py", 5) is None

    def test_new_file_diff(self):
        diff = """\
diff --git a/new.py b/new.py
--- /dev/null
+++ b/new.py
@@ -0,0 +1,3 @@
+line1
+line2
+line3
"""
        mapper = DiffLineMapper(diff)
        # /dev/null → file_path becomes "" after parsing
        # Actually the +++ line gives the file path: "b/new.py" → stripped to "new.py"
        actual = mapper.diff_line_to_file_line("new.py", 5)
        assert actual == 1  # new_start=1
