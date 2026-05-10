"""DiffGuard Agent Service - FastAPI entry point."""

from __future__ import annotations

import logging
import os
import re
import time
import uuid

from fastapi import FastAPI

from app.agent.pipeline_orchestrator import PipelineOrchestrator
from app.config import settings
from app.github.client import AsyncGitHubClient
from app.models.schemas import (
    DiffEntry,
    HealthResponse,
    ReviewMode,
    ReviewRequest,
    ReviewResponse,
    ReviewStatus,
    WebhookReviewRequest,
)

logger = logging.getLogger(__name__)

app = FastAPI(title="DiffGuard Agent Service", version="0.2.0")


@app.get("/api/v1/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(status="ok")


@app.post("/api/v1/review", response_model=ReviewResponse)
async def review(request: ReviewRequest) -> ReviewResponse:
    start = time.monotonic()
    request_id = request.request_id or str(uuid.uuid4())

    try:
        if request.mode == ReviewMode.PIPELINE:
            orchestrator = PipelineOrchestrator(request)
        else:
            return ReviewResponse(
                request_id=request_id,
                status=ReviewStatus.FAILED,
                error=f"Unsupported mode: {request.mode}",
            )

        result = await orchestrator.run()
        result.request_id = request_id
        result.review_duration_ms = int((time.monotonic() - start) * 1000)
        return result

    except Exception as e:
        logger.exception("Review failed for request %s", request_id)
        return ReviewResponse(
            request_id=request_id,
            status=ReviewStatus.FAILED,
            error=str(e),
            review_duration_ms=int((time.monotonic() - start) * 1000),
        )


@app.post("/api/v1/webhook-review", response_model=ReviewResponse)
async def webhook_review(request: WebhookReviewRequest) -> ReviewResponse:
    """Java gateway calls this with repo + PR number. Python fetches diff and runs review."""
    start = time.monotonic()
    request_id = request.request_id or str(uuid.uuid4())

    github_token = os.environ.get(request.github_token_env, "")
    if not github_token:
        return ReviewResponse(
            request_id=request_id,
            status=ReviewStatus.FAILED,
            error=f"GitHub token not found in env var {request.github_token_env}",
        )

    gh = AsyncGitHubClient(
        token=github_token,
        repo=request.repo_full_name,
        excluded_dirs=request.excluded_dirs,
        sha=request.head_sha,
    )

    try:
        # Fetch PR diff
        diff_text = await gh.get_pr_diff(request.pr_number)
        if not diff_text.strip():
            return ReviewResponse(
                request_id=request_id,
                status=ReviewStatus.COMPLETED,
                summary="Empty diff, nothing to review",
            )

        # Split diff into file-level entries
        diff_entries = _split_diff(diff_text)
        if not diff_entries:
            diff_entries = [DiffEntry(file_path="full-diff", content=diff_text)]

        # Build review request and run pipeline
        review_req = ReviewRequest(
            request_id=request_id,
            mode=ReviewMode.PIPELINE,
            project_dir=request.project_dir,
            diff_entries=diff_entries,
            llm_config=request.llm_config,
            review_config=request.review_config,
            tool_server_url=request.tool_server_url,
            allowed_files=[],
        )

        orchestrator = PipelineOrchestrator(review_req)
        result = await orchestrator.run()

        # Post review comments to GitHub
        if result.issues:
            visible_issues = [
                i.model_dump() for i in result.issues
                if not i.filter_metadata.get("excluded")
            ]
            try:
                await gh.post_review_comment(request.pr_number, visible_issues)
            except Exception as e:
                logger.warning("Failed to post GitHub comments: %s", e)

        result.request_id = request_id
        result.review_duration_ms = int((time.monotonic() - start) * 1000)
        return result

    except Exception as e:
        logger.exception("Webhook review failed for %s #%d", request.repo_full_name, request.pr_number)
        return ReviewResponse(
            request_id=request_id,
            status=ReviewStatus.FAILED,
            error=str(e),
            review_duration_ms=int((time.monotonic() - start) * 1000),
        )

    finally:
        await gh.close()


def _split_diff(diff_text: str) -> list[DiffEntry]:
    """Split a multi-file diff into per-file DiffEntry objects."""
    sections = re.split(r"(?=^diff --git)", diff_text, flags=re.MULTILINE)
    entries = []
    for section in sections:
        section = section.strip()
        if not section:
            continue
        match = re.match(r"^diff --git a/(.*?) b/", section)
        filepath = match.group(1) if match else "unknown"
        entries.append(DiffEntry(file_path=filepath, content=section))
    return entries


def main() -> None:
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host=settings.AGENT_HOST,
        port=settings.AGENT_PORT,
        log_level=settings.LOG_LEVEL,
    )


if __name__ == "__main__":
    main()
