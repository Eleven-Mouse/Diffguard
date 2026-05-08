"""Application settings loaded from environment variables."""

from __future__ import annotations

import os


class Settings:
    """Runtime configuration for the agent service."""

    # Java tool server
    JAVA_TOOL_SERVER_URL: str = os.getenv("JAVA_TOOL_SERVER_URL", "http://localhost:9090")
    DIFFGUARD_TOOL_SECRET: str | None = os.getenv("DIFFGUARD_TOOL_SECRET")

    # Agent HTTP server
    AGENT_HOST: str = os.getenv("AGENT_HOST", "0.0.0.0")
    AGENT_PORT: int = int(os.getenv("AGENT_PORT", "8000"))
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "info")

    # RabbitMQ
    RABBITMQ_HOST: str = os.getenv("RABBITMQ_HOST", "localhost")
    RABBITMQ_PORT: int = int(os.getenv("RABBITMQ_PORT", "5672"))
    RABBITMQ_USER: str = os.getenv("RABBITMQ_USER", "guest")
    RABBITMQ_PASSWORD: str = os.getenv("RABBITMQ_PASSWORD", "guest")

    # Redis
    REDIS_HOST: str = os.getenv("REDIS_HOST", "localhost")
    REDIS_PORT: int = int(os.getenv("REDIS_PORT", "6379"))

    # Mode: "http" (FastAPI only) or "worker" (RabbitMQ consumer only) or "both"
    AGENT_MODE: str = os.getenv("AGENT_MODE", "http")


settings = Settings()
