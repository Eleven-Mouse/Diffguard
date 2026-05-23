"""Lightweight GitHub API client — zero external dependencies beyond requests."""

from __future__ import annotations

import logging
import os
from typing import Any

import requests

from diffguard_agent.github.shared import (
    build_review_comments,
    build_summary_comment,
    filter_excluded,
    is_excluded,
)

logger = logging.getLogger(__name__)


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
        return filter_excluded(resp.text, self.excluded_dirs)

    def get_pr_files(self, pr_number: int) -> list[dict[str, Any]]:
        """Fetch the list of changed files."""
        url = f"{self._base}/pulls/{pr_number}/files?per_page=100"
        resp = requests.get(url, headers=self.headers, timeout=30)
        resp.raise_for_status()
        return [
            f for f in resp.json()
            if not is_excluded(f.get("filename", ""), self.excluded_dirs)
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

        comments = build_review_comments(issues, file_map)
        if not comments:
            return

        review_body = {
            "commit_id": os.environ.get("GITHUB_SHA", ""),
            "event": "COMMENT",
            "comments": comments,
            "body": build_summary_comment(issues),
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
    # Historical comments
    # ------------------------------------------------------------------

    def fetch_diffguard_comments(
        self, pr_number: int, *, max_chars: int = 2000,
    ) -> str:
        """Fetch previous DiffGuard review comments on this PR."""
        try:
            resp = requests.get(
                f"{self._base}/pulls/{pr_number}/comments?per_page=100",
                headers=self.headers,
                timeout=30,
            )
            resp.raise_for_status()
        except Exception as e:
            logger.warning("Failed to fetch PR comments: %s", e)
            return ""

        dg_comments = [
            c for c in resp.json()
            if "DiffGuard" in c.get("body", "") or c.get("body", "").startswith("**[")
        ]
        if not dg_comments:
            return ""

        lines: list[str] = []
        total = 0
        for c in dg_comments:
            entry = f"- {c.get('path', '?')}:{c.get('line', '?')} | {c['body'][:200]}"
            if total + len(entry) > max_chars:
                break
            lines.append(entry)
            total += len(entry)

        return "\n".join(lines)
