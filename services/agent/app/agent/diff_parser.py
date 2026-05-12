"""Unified diff parser and line number mapper.

Parses unified diff format and maps between:
  - Diff-context line numbers (absolute 1-based offset within the diff text)
  - Actual file line numbers (from the ``@@`` hunk header offsets)

Usage::

    mapper = DiffLineMapper(diff_text)
    actual = mapper.diff_line_to_file_line("src/Service.java", 6)
      # → 11  (actual line in the new version of the file)

The LLM's "line" field in an issue is a diff-context line number.
This module converts it to the real file line for accurate GitHub comment placement.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field


@dataclass
class FileHunk:
    """One hunk within a file's diff section."""
    old_start: int
    new_start: int
    old_count: int
    new_count: int
    # Mapping from diff-context absolute line number → actual new file line
    diff_offset_to_file_line: dict[int, int] = field(default_factory=dict)


class DiffLineMapper:
    """Parses unified diff and maps between diff-context and actual file line numbers.

    The diff-context line number is the 1-based absolute offset of a line within
    the full diff text. This matches the "line" field that LLMs output when they
    reference an issue location.
    """

    def __init__(self, diff_text: str) -> None:
        self._file_hunks: dict[str, list[FileHunk]] = {}
        self._file_line_index: dict[str, dict[int, int]] = {}
        self._parse(diff_text)

    def diff_line_to_file_line(
        self, file_path: str, diff_context_line: int
    ) -> int | None:
        """Convert a diff-context line number to actual file line number.

        Args:
            file_path: File path as it appears in the diff (e.g. "src/Service.java")
            diff_context_line: 1-based absolute offset within the diff text

        Returns:
            Line number in the new version of the file, or None if not mappable.
        """
        index = self._file_line_index.get(file_path)
        if index is None:
            return None
        return index.get(diff_context_line)

    def file_path_in_diff(self, file_path: str) -> bool:
        """Check if a file path appears in this diff."""
        return file_path in self._file_hunks

    def get_hunks(self, file_path: str) -> list[FileHunk]:
        """Return all hunks for a file."""
        return self._file_hunks.get(file_path, [])

    # ------------------------------------------------------------------
    # Internal: single-pass linear scan
    # ------------------------------------------------------------------

    def _parse(self, diff_text: str) -> None:
        """Parse the unified diff with a single linear scan.

        For each hunk we track:
          - ``new_file_line``: the current line number in the new (post-patch) file.
            Starts at ``new_start`` from the ``@@`` header and increments for
            context lines (`` `` prefix) and added lines (``+`` prefix).
          - ``diff_abs``: the 1-based absolute line number in the diff text.
        """
        current_file: str | None = None
        in_hunk: bool = False
        new_file_line: int = 0

        for diff_abs, raw_line in enumerate(diff_text.splitlines(), start=1):

            # --- / +++ headers: track current file ---
            if raw_line.startswith("+++ "):
                in_hunk = False
                path = raw_line[4:].split("\t")[0].rstrip()
                if path.startswith("b/"):
                    path = path[2:]
                if path.startswith("/dev/null"):
                    path = ""
                current_file = path
                if current_file not in self._file_hunks:
                    self._file_hunks[current_file] = []
                if current_file not in self._file_line_index:
                    self._file_line_index[current_file] = {}
                continue

            if raw_line.startswith("--- "):
                in_hunk = False
                continue

            if raw_line.startswith("diff "):
                in_hunk = False
                current_file = None
                continue

            # @@ hunk header ---
            if raw_line.startswith("@@ ") and current_file is not None:
                m = re.match(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))?", raw_line)
                if not m:
                    continue
                new_file_line = int(m.group(3))
                in_hunk = True
                continue

            # Hunk body ---
            if in_hunk and current_file is not None:
                if raw_line.startswith("+"):
                    # Added line: record mapping and advance new-file pointer
                    self._file_line_index[current_file][diff_abs] = new_file_line
                    new_file_line += 1
                elif raw_line.startswith("-"):
                    # Deleted line: don't advance new-file pointer
                    pass
                elif raw_line.startswith(" "):
                    # Context line: advance new-file pointer (no mapping recorded)
                    new_file_line += 1
                elif raw_line.startswith("\\"):
                    # "\ No newline at end of file" — skip
                    pass
                elif raw_line == "":
                    # Empty line inside hunk body: treat as context line
                    new_file_line += 1
                else:
                    # Any other line inside a hunk → end of hunk
                    in_hunk = False


# ---------------------------------------------------------------------------
# Convenience function
# ---------------------------------------------------------------------------

def map_issue_line(file_path: str, diff_context_line: int, diff_text: str) -> int | None:
    """One-shot: map a diff-context line to actual file line."""
    mapper = DiffLineMapper(diff_text)
    return mapper.diff_line_to_file_line(file_path, diff_context_line)