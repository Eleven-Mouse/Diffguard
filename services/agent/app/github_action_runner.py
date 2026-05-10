#!/usr/bin/env python3
"""DiffGuard GitHub Action Runner.

Standalone entry point for running code review inside GitHub Actions.
Zero external services needed — no MySQL, RabbitMQ, Redis, or Java Tool Server.

Flow:
  1. Read PR number + repo from environment
  2. Fetch PR diff via GitHub API
  3. Run the 4-stage pipeline (summary → review → aggregate → FP filter)
  4. Post review comments on the PR
  5. Output JSON result to stdout
"""

from __future__ import annotations

import json
import logging
import os
import sys
import time
from pathlib import Path

logging.basicConfig(
    level=logging.INFO,
    format="[%(name)s] %(message)s",
    stream=sys.stderr,
)
logger = logging.getLogger("diffguard")


def _env(key: str, default: str = "") -> str:
    return os.environ.get(key, default).strip()


def _build_review_request(diff_text: str):
    """Construct a ReviewRequest from environment variables."""
    from app.models.schemas import (
        DiffEntry,
        LlmConfig,
        ReviewConfigPayload,
        ReviewMode,
        ReviewRequest,
    )

    provider = _env("DIFFGUARD_PROVIDER", "claude")
    model = _env("DIFFGUARD_MODEL", "claude-sonnet-4-20250514")
    api_key = _env("DIFFGUARD_API_KEY")

    if not api_key:
        raise RuntimeError("DIFFGUARD_API_KEY is required")

    return ReviewRequest(
        request_id=f"gh-{_env('GITHUB_REPOSITORY')}-{_env('PR_NUMBER')}",
        mode=ReviewMode.PIPELINE,
        project_dir=_env("REPO_PATH", str(Path.cwd())),
        diff_entries=[DiffEntry(file_path="full-diff", content=diff_text)],
        llm_config=LlmConfig(
            provider=provider,
            model=model,
            api_key=api_key,
        ),
        review_config=ReviewConfigPayload(
            language=_env("DIFFGUARD_LANGUAGE", "zh"),
        ),
        tool_server_url="",  # no tool server in action mode
        allowed_files=[],
    )


def _post_comments(issues: list[dict], pr_number: int) -> None:
    """Post PR comments if DIFFGUARD_COMMENT_PR is true."""
    if _env("DIFFGUARD_COMMENT_PR", "true").lower() != "true":
        return

    from app.github_api import GitHubClient

    client = GitHubClient()
    client.post_review_comment(pr_number, issues)


def main() -> None:
    start = time.time()

    # --- Validate environment ---
    repo = _env("GITHUB_REPOSITORY")
    pr_str = _env("PR_NUMBER")
    if not repo or not pr_str:
        print(json.dumps({"error": "GITHUB_REPOSITORY and PR_NUMBER are required"}))
        sys.exit(1)
    pr_number = int(pr_str)

    logger.info("DiffGuard starting: %s #%d", repo, pr_number)

    # --- Fetch PR diff ---
    from app.github_api import GitHubClient

    gh = GitHubClient()
    meta = gh.get_pr_metadata(pr_number)
    diff_text = gh.get_pr_diff(pr_number)

    if not diff_text.strip():
        logger.info("Empty diff, nothing to review")
        print(json.dumps({"pr": pr_number, "issues": [], "summary": "Empty diff"}))
        sys.exit(0)

    logger.info(
        "PR #%d: %s (%d additions, %d deletions, %d files)",
        pr_number, meta["title"], meta["additions"], meta["deletions"], meta["changed_files"],
    )

    # --- Build request & run pipeline ---
    from app.agent.pipeline_orchestrator import PipelineOrchestrator

    request = _build_review_request(diff_text)
    orchestrator = PipelineOrchestrator(request)

    import asyncio

    response = asyncio.run(orchestrator.run())

    duration_ms = int((time.time() - start) * 1000)
    logger.info("Review completed in %d ms, status=%s", duration_ms, response.status)

    # --- Build output ---
    visible_issues = [
        i.model_dump() for i in response.issues
        if not i.filter_metadata.get("excluded")
    ]

    output = {
        "pr": pr_number,
        "repo": repo,
        "status": response.status.value,
        "has_critical": response.has_critical_flag,
        "issues": visible_issues,
        "summary": response.summary,
        "duration_ms": duration_ms,
    }

    # --- Post PR comments ---
    try:
        _post_comments(visible_issues, pr_number)
    except Exception as e:
        logger.warning("Failed to post comments: %s", e)

    # --- Output JSON to stdout ---
    print(json.dumps(output, ensure_ascii=False, indent=2))

    if response.status.value == "failed":
        sys.exit(1)


if __name__ == "__main__":
    main()
