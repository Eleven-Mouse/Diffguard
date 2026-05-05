"""DiffGuard Agent Service - FastAPI + RabbitMQ worker entry point."""

from __future__ import annotations

import asyncio
import time
import uuid

import langchain
from fastapi import FastAPI

from app.agent.pipeline_orchestrator import PipelineOrchestrator
from app.agent.multi_agent_orchestrator import MultiAgentOrchestrator
from app.config import settings
from app.models.schemas import (
    HealthResponse,
    ReviewMode,
    ReviewRequest,
    ReviewResponse,
    ReviewStatus,
)

app = FastAPI(title="DiffGuard Agent Service", version="0.1.0")

# RabbitMQ consumer (lazy init)
_rabbitmq_consumer = None


@app.get("/api/v1/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(
        status="ok",
        langchain_version=langchain.__version__,
    )


@app.post("/api/v1/review", response_model=ReviewResponse)
async def review(request: ReviewRequest) -> ReviewResponse:
    start = time.monotonic()
    request_id = request.request_id or str(uuid.uuid4())

    # Propagate trace ID for cross-service correlation
    import logging
    logger = logging.getLogger("diffguard.review")
    logger.info("Review request: id=%s mode=%s trace_id=%s", request_id, request.mode, request_id)

    try:
        if request.mode == ReviewMode.PIPELINE:
            orchestrator = PipelineOrchestrator(request)
        elif request.mode == ReviewMode.MULTI_AGENT:
            orchestrator = MultiAgentOrchestrator(request)
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
        return ReviewResponse(
            request_id=request_id,
            status=ReviewStatus.FAILED,
            error=str(e),
            review_duration_ms=int((time.monotonic() - start) * 1000),
        )


# --- RabbitMQ Worker Mode ---

async def _run_rabbitmq_worker() -> None:
    """Start RabbitMQ consumer as a background task."""
    global _rabbitmq_consumer
    from app.messaging.rabbitmq_consumer import ReviewTaskConsumer
    _rabbitmq_consumer = ReviewTaskConsumer()
    await _rabbitmq_consumer.start()
    # Keep running forever
    try:
        await asyncio.Future()
    except asyncio.CancelledError:
        await _rabbitmq_consumer.stop()


@app.on_event("startup")
async def _startup() -> None:
    """Start RabbitMQ consumer if configured."""
    if settings.AGENT_MODE in ("worker", "both"):
        asyncio.create_task(_run_rabbitmq_worker())


@app.on_event("shutdown")
async def _shutdown() -> None:
    if _rabbitmq_consumer:
        await _rabbitmq_consumer.stop()


def main() -> None:
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.AGENT_HOST,
        port=settings.AGENT_PORT,
        log_level=settings.LOG_LEVEL,
    )


def run_worker() -> None:
    """Run as pure RabbitMQ worker (no HTTP server)."""
    import logging
    logging.basicConfig(level=getattr(logging, settings.LOG_LEVEL.upper()))

    async def _run() -> None:
        from app.messaging.rabbitmq_consumer import ReviewTaskConsumer
        consumer = ReviewTaskConsumer()
        await consumer.start()
        try:
            await asyncio.Future()
        except (KeyboardInterrupt, asyncio.CancelledError):
            await consumer.stop()

    asyncio.run(_run())


if __name__ == "__main__":
    if settings.AGENT_MODE == "worker":
        run_worker()
    else:
        main()
