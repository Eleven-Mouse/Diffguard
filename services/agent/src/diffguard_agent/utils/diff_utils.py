"""Shared diff parsing utilities."""

from __future__ import annotations

import re

from diffguard_agent.models.schemas import DiffEntry


def split_diff(diff_text: str) -> list[DiffEntry]:
    """Split a multi-file unified diff into per-file DiffEntry objects."""
    sections = re.split(r"(?=^diff --git)", diff_text, flags=re.MULTILINE)
    entries: list[DiffEntry] = []
    for section in sections:
        section = section.strip()
        if not section:
            continue
        match = re.match(r"^diff --git a/(.*?) b/", section)
        filepath = match.group(1) if match else "unknown"
        entries.append(DiffEntry(file_path=filepath, content=section))
    return entries if entries else [DiffEntry(file_path="full-diff", content=diff_text)]
