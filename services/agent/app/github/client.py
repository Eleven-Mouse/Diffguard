"""Async GitHub API client using httpx."""

from __future__ import annotations

import logging
import os
import re
from typing import Any

import httpx

logger = logging.getLogger(__name__)

_GITHUB_API = "https://api.github.com"


class AsyncGitHubClient:
    """Async GitHub REST API client for fetching PR data and posting reviews."""

    def __init__(
        self,
        token: str,
        repo: str,
        *,
        excluded_dirs: list[str] | None = None,
        sha: str = "",
    ) -> None:
        self.token = token
        self.repo = repo
        self.sha = sha
        self._base = f"{_GITHUB_API}/repos/{repo}"
        self._headers = {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github.v3+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        self.excluded_dirs = excluded_dirs or []
        self._client: httpx.AsyncClient | None = None

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient(timeout=httpx.Timeout(60.0))
        return self._client

    async def close(self) -> None:
        if self._client:
            await self._client.aclose()
            self._client = None

    # ------------------------------------------------------------------
    # PR data
    # ------------------------------------------------------------------

    async def get_pr_diff(self, pr_number: int) -> str:
        """Fetch the unified diff for a pull request."""
        client = await self._get_client()
        resp = await client.get(
            f"{self._base}/pulls/{pr_number}",
            headers={**self._headers, "Accept": "application/vnd.github.diff"},
        )
        resp.raise_for_status()
        return self._filter_excluded(resp.text)

    async def get_pr_files(self, pr_number: int) -> list[dict[str, Any]]:
        """Fetch the list of changed files."""
        client = await self._get_client()
        resp = await client.get(
            f"{self._base}/pulls/{pr_number}/files?per_page=100",
            headers=self._headers,
        )
        resp.raise_for_status()
        return [
            f for f in resp.json()
            if not self._is_excluded(f.get("filename", ""))
        ]

    async def get_pr_metadata(self, pr_number: int) -> dict[str, Any]:
        """Fetch lightweight PR metadata."""
        client = await self._get_client()
        resp = await client.get(
            f"{self._base}/pulls/{pr_number}",
            headers=self._headers,
        )
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

    async def post_review_comment(
        self,
        pr_number: int,
        issues: list[dict],
    ) -> None:
        """Post an inline review with findings as comments on specific lines."""
        if not issues:
            return

        pr_files = await self.get_pr_files(pr_number)
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

        commit_id = self.sha or os.environ.get("GITHUB_SHA", "")
        review_body = {
            "commit_id": commit_id,
            "event": "COMMENT",
            "comments": comments,
            "body": _build_summary_comment(issues),
        }

        client = await self._get_client()
        try:
            resp = await client.post(
                f"{self._base}/pulls/{pr_number}/reviews",
                headers=self._headers,
                json=review_body,
            )
            resp.raise_for_status()
        except httpx.HTTPStatusError:
            logger.warning("Review post failed, falling back to individual comments")
            for c in comments:
                try:
                    await client.post(
                        f"{self._base}/pulls/{pr_number}/comments",
                        headers=self._headers,
                        json={**c, "commit_id": commit_id},
                    )
                except httpx.HTTPStatusError:
                    pass

    # ------------------------------------------------------------------
    # Historical comments
    # ------------------------------------------------------------------

    async def fetch_diffguard_comments(
        self, pr_number: int, *, max_chars: int = 2000,
    ) -> str:
        """Fetch previous DiffGuard review comments on this PR."""
        client = await self._get_client()
        try:
            resp = await client.get(
                f"{self._base}/pulls/{pr_number}/comments?per_page=100",
                headers=self._headers,
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
