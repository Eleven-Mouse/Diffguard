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
    IssuePayload,
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
        await self.channel.declare_exchange(
            "review.dlx", aio_pika.ExchangeType.DIRECT, durable=True,
        )

        # Queues and precise bindings (avoid duplicate consumption).
        queue_bindings = {
            "review.agent.queue": ["review.multi_agent.task", "review.agent.task"],
            "review.pipeline.queue": ["review.pipeline.task"],
        }
        for q_name, bindings in queue_bindings.items():
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
            for routing_key in bindings:
                await queue.bind(exchange, routing_key=routing_key)
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
                task_id = self._resolve_task_id(data)
                mode = data.get("mode", "PIPELINE")
                logger.info("Received task: id=%s mode=%s", task_id, mode)

                # Build ReviewRequest from message
                request = self._build_request(data)
                request.request_id = task_id
                request.task_id = task_id

                if settings.AGENT_MQ_MOCK_MODE == "success":
                    result = self._mock_success_result(task_id, mode)
                    await self._publish_result(result)
                    logger.info("Task %s completed by mock MQ mode", task_id)
                    return

                # Dispatch to orchestrator
                if mode == ReviewMode.PIPELINE.value:
                    orchestrator = PipelineOrchestrator(request)
                elif mode == ReviewMode.MULTI_AGENT.value:
                    orchestrator = MultiAgentOrchestrator(request)
                else:
                    raise ValueError(f"Unsupported mode for agent worker: {mode}")

                result = await orchestrator.run()
                result.task_id = task_id
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
                    task_id=task_id,
                    request_id=task_id,
                    status=ReviewStatus.FAILED,
                    error=str(e),
                    review_duration_ms=int((time.monotonic() - start) * 1000),
                )
                await self._publish_result(error_result)

    def _build_request(self, data: dict[str, Any]) -> ReviewRequest:
        """Build ReviewRequest from RabbitMQ message payload."""
        task_id = self._resolve_task_id(data)
        llm_data = data.get("llm_config", {})
        review_data = data.get("review_config", {})
        entries_data = data.get("diff_entries", [])

        return ReviewRequest(
            request_id=task_id,
            task_id=task_id,
            mode=ReviewMode(data.get("mode", "PIPELINE")),
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
                api_key="",
                api_key_env=llm_data.get("api_key_env", "DIFFGUARD_API_KEY"),
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

    @staticmethod
    def _resolve_task_id(data: dict[str, Any]) -> str:
        task_id = (data.get("task_id") or "").strip()
        if task_id:
            return task_id
        request_id = (data.get("request_id") or "").strip()
        if request_id:
            return request_id
        return "unknown"

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

    @staticmethod
    def _mock_success_result(task_id: str, mode: str) -> ReviewResponse:
        return ReviewResponse(
            task_id=task_id,
            request_id=task_id,
            status=ReviewStatus.COMPLETED,
            has_critical_flag=False,
            issues=[
                IssuePayload(
                    severity="INFO",
                    file="mock/" + mode.lower() + ".txt",
                    line=1,
                    type="mock-success",
                    message="mock MQ integration success",
                    suggestion="disable AGENT_MQ_MOCK_MODE for real LLM execution",
                )
            ],
            total_tokens_used=0,
            review_duration_ms=1,
            summary="mock-success",
        )

    async def stop(self) -> None:
        """Graceful shutdown."""
        if self.connection:
            await self.connection.close()
            logger.info("RabbitMQ consumer stopped")
