"""Lightweight GitHub API client — zero external dependencies beyond requests."""

from __future__ import annotations

import os
import re
from typing import Any

import requests


class GitHubClient:
    """Fetches PR data and posts review comments via the GitHub REST API."""

    def __init__(self) -> None:
        self.token = os.environ.get("GITHUB_TOKEN", "")
        self.repo = os.environ.get("GITHUB_REPOSITORY", "")
        if not self.token or not self.repo:
            raise RuntimeError("GITHUB_TOKEN and GITHUB_REPOSITORY must be set")

        self.headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github.v3+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        self._base = f"https://api.github.com/repos/{self.repo}"

        exclude_raw = os.environ.get("DIFFGUARD_EXCLUDE_DIRS", "")
        self.excluded_dirs = [d.strip() for d in exclude_raw.split(",") if d.strip()]

    # ------------------------------------------------------------------
    # PR data
    # ------------------------------------------------------------------

    def get_pr_diff(self, pr_number: int) -> str:
        """Fetch the unified diff for a pull request."""
        url = f"{self._base}/pulls/{pr_number}"
        headers = {**self.headers, "Accept": "application/vnd.github.diff"}
        resp = requests.get(url, headers=headers, timeout=60)
        resp.raise_for_status()
        return self._filter_excluded(resp.text)

    def get_pr_files(self, pr_number: int) -> list[dict[str, Any]]:
        """Fetch the list of changed files."""
        url = f"{self._base}/pulls/{pr_number}/files?per_page=100"
        resp = requests.get(url, headers=self.headers, timeout=30)
        resp.raise_for_status()
        return [
            f for f in resp.json()
            if not self._is_excluded(f.get("filename", ""))
        ]

    def get_pr_metadata(self, pr_number: int) -> dict[str, Any]:
        """Fetch lightweight PR metadata."""
        url = f"{self._base}/pulls/{pr_number}"
        resp = requests.get(url, headers=self.headers, timeout=30)
        resp.raise_for_status()
        d = resp.json()
        return {
            "number": d["number"],
            "title": d["title"],
            "user": d["user"]["login"],
            "additions": d["additions"],
            "deletions": d["deletions"],
            "changed_files": d["changed_files"],
        }

    # ------------------------------------------------------------------
    # PR comments
    # ------------------------------------------------------------------

    def post_review_comment(self, pr_number: int, issues: list[dict]) -> None:
        """Post an inline review with findings as comments on specific lines."""
        if not issues:
            return

        pr_files = self.get_pr_files(pr_number)
        file_map = {f["filename"]: f for f in pr_files}

        comments = []
        for issue in issues:
            if issue.get("filter_metadata", {}).get("excluded"):
                continue
            filepath = issue.get("file", "")
            if filepath not in file_map:
                continue

            line = issue.get("line") or 1
            severity = issue.get("severity", "INFO")
            msg_type = issue.get("type", "")
            message = issue.get("message", "")
            suggestion = issue.get("suggestion", "")

            body = f"**[{severity}] {msg_type}**: {message}"
            if suggestion:
                body += f"\n\n> **Suggestion**: {suggestion}"

            comments.append({
                "path": filepath,
                "line": line,
                "side": "RIGHT",
                "body": body,
            })

        if not comments:
            return

        meta = self.get_pr_metadata(pr_number)
        review_body = {
            "commit_id": os.environ.get("GITHUB_SHA", ""),
            "event": "COMMENT",
            "comments": comments,
            "body": _build_summary_comment(issues),
        }

        try:
            resp = requests.post(
                f"{self._base}/pulls/{pr_number}/reviews",
                headers=self.headers,
                json=review_body,
                timeout=30,
            )
            resp.raise_for_status()
        except requests.RequestException:
            # Fallback: post individual comments
            for c in comments:
                try:
                    requests.post(
                        f"{self._base}/pulls/{pr_number}/comments",
                        headers=self.headers,
                        json={**c, "commit_id": review_body["commit_id"]},
                        timeout=30,
                    )
                except requests.RequestException:
                    pass

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _is_excluded(self, filepath: str) -> bool:
        for d in self.excluded_dirs:
            d = d.lstrip("./")
            if filepath.startswith(d + "/") or f"/{d}/" in filepath:
                return True
        return False

    def _filter_excluded(self, diff_text: str) -> str:
        sections = re.split(r"(?=^diff --git)", diff_text, flags=re.MULTILINE)
        result = []
        for section in sections:
            if not section.strip():
                continue
            match = re.match(r"^diff --git a/(.*?) b/", section)
            if match and self._is_excluded(match.group(1)):
                continue
            result.append(section)
        return "".join(result)


def _build_summary_comment(issues: list[dict]) -> str:
    visible = [i for i in issues if not i.get("filter_metadata", {}).get("excluded")]
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
