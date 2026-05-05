"""RabbitMQ consumer - receives review tasks and dispatches to orchestrators."""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

import aio_pika
from aio_pika.abc import AbstractIncomingMessage

from app.agent.multi_agent_orchestrator import MultiAgentOrchestrator
from app.agent.pipeline_orchestrator import PipelineOrchestrator
from app.config import settings
from app.models.schemas import (
    DiffEntry,
    LlmConfig,
    ReviewConfigPayload,
    ReviewMode,
    ReviewRequest,
    ReviewResponse,
    ReviewStatus,
)

logger = logging.getLogger(__name__)


class ReviewTaskConsumer:
    """Consumes review tasks from RabbitMQ and dispatches to orchestrators."""

    def __init__(self) -> None:
        self.connection: aio_pika.RobustConnection | None = None
        self.channel: aio_pika.RobustChannel | None = None
        self._consumer_tag: str | None = None

    async def start(self) -> None:
        """Connect to RabbitMQ and start consuming."""
        url = (
            f"amqp://{settings.RABBITMQ_USER}:{settings.RABBITMQ_PASSWORD}"
            f"@{settings.RABBITMQ_HOST}:{settings.RABBITMQ_PORT}/"
        )
        self.connection = await aio_pika.connect_robust(url)
        self.channel = await self.connection.channel()
        await self.channel.set_qos(prefetch_count=1)

        # Declare topology (idempotent)
        exchange = await self.channel.declare_exchange(
            "review.exchange", aio_pika.ExchangeType.TOPIC, durable=True,
        )
        dlx = await self.channel.declare_exchange(
            "review.dlx", aio_pika.ExchangeType.DIRECT, durable=True,
        )

        # Queues
        queues = ["review.agent.queue", "review.pipeline.queue"]
        for q_name in queues:
            queue = await self.channel.declare_queue(
                q_name,
                durable=True,
                arguments={
                    "x-dead-letter-exchange": "review.dlx",
                    "x-dead-letter-routing-key": "dead",
                    "x-max-priority": 10,
                    "x-message-ttl": 600000,
                },
            )
            await queue.bind(exchange, routing_key=f"review.*.task")
            self._consumer_tag = await queue.consume(self._on_message)

        # Result queue for publishing results back
        self._result_queue = await self.channel.declare_queue(
            "review.result.queue", durable=True,
        )
        await self._result_queue.bind(exchange, routing_key="review.result.#")

        logger.info("RabbitMQ consumer started, listening for review tasks")

    async def _on_message(self, message: AbstractIncomingMessage) -> None:
        """Handle incoming review task message."""
        async with message.process(requeue=False):
            body = message.body.decode("utf-8")
            task_id = "unknown"
            start = time.monotonic()

            try:
                import json
                data = json.loads(body)
                task_id = data.get("task_id", "unknown")
                mode = data.get("mode", "PIPELINE")
                logger.info("Received task: id=%s mode=%s", task_id, mode)

                # Build ReviewRequest from message
                request = self._build_request(data)
                request.request_id = task_id

                # Dispatch to orchestrator
                if mode == ReviewMode.PIPELINE.value:
                    orchestrator = PipelineOrchestrator(request)
                elif mode == ReviewMode.MULTI_AGENT.value:
                    orchestrator = MultiAgentOrchestrator(request)
                else:
                    logger.warning("Unknown mode: %s, defaulting to MULTI_AGENT", mode)
                    orchestrator = MultiAgentOrchestrator(request)

                result = await orchestrator.run()
                result.request_id = task_id
                result.review_duration_ms = int((time.monotonic() - start) * 1000)

                # Publish result back to result queue
                await self._publish_result(result)

                logger.info(
                    "Task %s completed: status=%s issues=%d duration=%dms",
                    task_id, result.status, len(result.issues), result.review_duration_ms,
                )

            except Exception as e:
                logger.exception("Task %s failed: %s", task_id, e)
                error_result = ReviewResponse(
                    request_id=task_id,
                    status=ReviewStatus.FAILED,
                    error=str(e),
                    review_duration_ms=int((time.monotonic() - start) * 1000),
                )
                await self._publish_result(error_result)

    def _build_request(self, data: dict[str, Any]) -> ReviewRequest:
        """Build ReviewRequest from RabbitMQ message payload."""
        llm_data = data.get("llm_config", {})
        review_data = data.get("review_config", {})
        entries_data = data.get("diff_entries", [])

        return ReviewRequest(
            request_id=data.get("task_id", ""),
            mode=ReviewMode(data.get("mode", "MULTI_AGENT")),
            project_dir=data.get("project_dir", ""),
            diff_entries=[
                DiffEntry(
                    file_path=e.get("file_path", ""),
                    content=e.get("content", ""),
                    token_count=e.get("token_count", 0),
                )
                for e in entries_data
            ],
            llm_config=LlmConfig(
                provider=llm_data.get("provider", "openai"),
                model=llm_data.get("model", "gpt-4o"),
                api_key=llm_data.get("api_key", ""),
                base_url=llm_data.get("base_url"),
                max_tokens=llm_data.get("max_tokens", 16384),
                temperature=llm_data.get("temperature", 0.3),
                timeout_seconds=llm_data.get("timeout_seconds", 300),
            ),
            review_config=ReviewConfigPayload(
                language=review_data.get("language", "zh"),
                rules_enabled=review_data.get("rules_enabled", ["security"]),
            ),
            tool_server_url=data.get("tool_server_url", settings.JAVA_TOOL_SERVER_URL),
            allowed_files=data.get("allowed_files", []),
        )

    async def _publish_result(self, result: ReviewResponse) -> None:
        """Publish review result to result queue."""
        if self.channel is None:
            return
        exchange = await self.channel.get_exchange("review.exchange")
        await exchange.publish(
            aio_pika.Message(
                body=result.model_dump_json().encode(),
                content_type="application/json",
                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
            ),
            routing_key=f"review.result.{result.status.value}",
        )

    async def stop(self) -> None:
        """Graceful shutdown."""
        if self.connection:
            await self.connection.close()
            logger.info("RabbitMQ consumer stopped")
