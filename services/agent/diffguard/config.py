"""Application settings loaded from environment variables."""

from __future__ import annotations

import os


def _as_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


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

    # Unstructured + ChromaDB context ingest
    CONTEXT_INGEST_ENABLED: bool = _as_bool("CONTEXT_INGEST_ENABLED", True)
    CHROMA_URL: str = os.getenv("CHROMA_URL", "http://localhost:8000")
    CHROMA_COLLECTION: str = os.getenv("CHROMA_COLLECTION", "diffguard_context_chunks")
    CHROMA_API_TOKEN: str | None = os.getenv("CHROMA_API_TOKEN")
    UNSTRUCTURED_MAX_CHUNK_CHARS: int = int(os.getenv("UNSTRUCTURED_MAX_CHUNK_CHARS", "1200"))
    UNSTRUCTURED_CHUNK_OVERLAP: int = int(os.getenv("UNSTRUCTURED_CHUNK_OVERLAP", "200"))

    # Mode: "http" (FastAPI only) or "worker" (RabbitMQ consumer only) or "both"
    AGENT_MODE: str = os.getenv("AGENT_MODE", "http")
    # Optional: "off" | "success". success mode bypasses LLM/tool execution and
    # returns deterministic MQ success responses for integration verification.
    AGENT_MQ_MOCK_MODE: str = os.getenv("AGENT_MQ_MOCK_MODE", "off").strip().lower()


settings = Settings()
