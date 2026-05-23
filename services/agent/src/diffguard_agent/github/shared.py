"""Shared GitHub client utilities — sync/async agnostic business logic."""

from __future__ import annotations

import re
from typing import Any


def is_excluded(filepath: str, excluded_dirs: list[str]) -> bool:
    """Check if a file path falls under any excluded directory."""
    for d in excluded_dirs:
        d = d.lstrip("./")
        if filepath.startswith(d + "/") or f"/{d}/" in filepath:
            return True
    return False


def filter_excluded(diff_text: str, excluded_dirs: list[str]) -> str:
    """Remove diff sections for files in excluded directories."""
    sections = re.split(r"(?=^diff --git)", diff_text, flags=re.MULTILINE)
    result: list[str] = []
    for section in sections:
        if not section.strip():
            continue
        match = re.match(r"^diff --git a/(.*?) b/", section)
        if match and is_excluded(match.group(1), excluded_dirs):
            continue
        result.append(section)
    return "".join(result)


def build_comment_body(
    severity: str,
    msg_type: str,
    message: str,
    suggestion: str = "",
) -> str:
    """Format a single inline review comment body."""
    body = f"**[{severity}] {msg_type}**: {message}"
    if suggestion:
        body += f"\n\n> **Suggestion**: {suggestion}"
    return body


def build_review_comments(
    issues: list[dict[str, Any]],
    file_map: dict[str, Any],
) -> list[dict[str, Any]]:
    """Build the list of inline review comment payloads from issues."""
    comments: list[dict[str, Any]] = []
    for issue in issues:
        if issue.get("filter_metadata", {}).get("excluded"):
            continue
        filepath = issue.get("file", "")
        if filepath not in file_map:
            continue

        comments.append({
            "path": filepath,
            "line": issue.get("line") or 1,
            "side": "RIGHT",
            "body": build_comment_body(
                severity=issue.get("severity", "INFO"),
                msg_type=issue.get("type", ""),
                message=issue.get("message", ""),
                suggestion=issue.get("suggestion", ""),
            ),
        })
    return comments


def build_summary_comment(issues: list[dict[str, Any]]) -> str:
    """Build the top-level review summary comment."""
    visible = [
        i for i in issues
        if not i.get("filter_metadata", {}).get("excluded")
    ]
    if not visible:
        return "**DiffGuard**: No actionable findings."

    critical = sum(1 for i in visible if i.get("severity") == "CRITICAL")
    warning = sum(1 for i in visible if i.get("severity") == "WARNING")
    info = sum(1 for i in visible if i.get("severity") == "INFO")

    lines = ["**DiffGuard AI Code Review**\n"]
    lines.append(f"Found **{len(visible)}** findings: ")
    if critical:
        lines.append(f"🔴 {critical} Critical")
    if warning:
        lines.append(f"🟡 {warning} Warning")
    if info:
        lines.append(f"🔵 {info} Info")
    lines.append("\n---\n*Powered by [DiffGuard](https://github.com/user/diffguard)*")
    return " ".join(lines)
