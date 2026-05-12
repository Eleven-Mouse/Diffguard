"""DiffGuard Agent Service - FastAPI entry point."""

from __future__ import annotations

import hashlib
import hmac
import logging
import os
import re
import time
import uuid

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

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

app = FastAPI(title="DiffGuard Agent Service", version="0.3.0")


# ---------------------------------------------------------------------------
# Webhook HMAC verification
# ---------------------------------------------------------------------------

_SIG_HEADER = "X-DiffGuard-Signature"
_TS_HEADER = "X-DiffGuard-Timestamp"
_MAX_SKEW_SECONDS = 300  # reject requests older than 5 minutes


def _verify_webhook_signature(body: bytes, request: Request) -> JSONResponse | None:
    """Verify HMAC-SHA256 signature on incoming webhook requests.

    Returns a JSONResponse error if verification fails, or None on success.
    If ``DIFFGUARD_WEBHOOK_HMAC_SECRET`` is not configured, verification is skipped.
    """
    secret = settings.WEBHOOK_HMAC_SECRET
    if not secret:
        return None  # No secret configured — skip verification

    sig_header = request.headers.get(_SIG_HEADER)
    if not sig_header:
        logger.warning("Webhook rejected: missing %s header", _SIG_HEADER)
        return JSONResponse(
            status_code=401,
            content={"detail": f"Missing {_SIG_HEADER} header"},
        )

    # Replay protection: check timestamp
    ts_header = request.headers.get(_TS_HEADER)
    if ts_header:
        try:
            ts = int(ts_header)
            if abs(time.time() - ts) > _MAX_SKEW_SECONDS:
                logger.warning("Webhook rejected: timestamp skew too large")
                return JSONResponse(
                    status_code=401,
                    content={"detail": "Request timestamp expired"},
                )
        except (ValueError, TypeError):
            pass  # malformed timestamp — don't reject, HMAC still checked

    # Compute expected signature: HMAC-SHA256(secret, body)
    expected = "sha256=" + hmac.new(
        secret.encode("utf-8"), body, hashlib.sha256,
    ).hexdigest()

    if not hmac.compare_digest(expected, sig_header):
        logger.warning("Webhook rejected: invalid signature")
        return JSONResponse(
            status_code=401,
            content={"detail": "Invalid signature"},
        )

    return None  # verification passed


# ---------------------------------------------------------------------------
# Middleware: capture raw body for HMAC verification
# ---------------------------------------------------------------------------

@app.middleware("http")
async def webhook_signature_middleware(request: Request, call_next):
    """Verify HMAC signature on /webhook-review before routing."""
    if request.url.path != "/api/v1/webhook-review" or request.method != "POST":
        return await call_next(request)

    body = await request.body()
    err = _verify_webhook_signature(body, request)
    if err is not None:
        return err

    return await call_next(request)


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------


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

        # Fetch historical DiffGuard comments on this PR
        historical_context = ""
        try:
            historical_context = await gh.fetch_diffguard_comments(request.pr_number)
        except Exception as e:
            logger.warning("Failed to fetch historical comments: %s", e)

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

        orchestrator = PipelineOrchestrator(review_req, historical_context=historical_context)
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