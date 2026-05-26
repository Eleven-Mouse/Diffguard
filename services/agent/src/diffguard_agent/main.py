"""DiffGuard Agent Service - FastAPI entry point."""

from __future__ import annotations

import logging
import time
import uuid

from fastapi import FastAPI

from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator
from diffguard_agent.config import settings
from diffguard_agent.models.schemas import (
    HealthResponse,
    ReviewMode,
    ReviewRequest,
    ReviewResponse,
    ReviewStatus,
)

logger = logging.getLogger(__name__)

app = FastAPI(title="DiffGuard Agent Service", version="0.2.0")


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
        if request.mode in (ReviewMode.PIPELINE, ReviewMode.MULTI_AGENT):
            # Keep a single execution path for now.
            # MULTI_AGENT is currently an API-compatible alias that
            # reuses pipeline execution.
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


def main() -> None:
    import uvicorn
    uvicorn.run(
        "diffguard_agent.main:app",
        host=settings.AGENT_HOST,
        port=settings.AGENT_PORT,
        log_level=settings.LOG_LEVEL,
    )


if __name__ == "__main__":
    main()
