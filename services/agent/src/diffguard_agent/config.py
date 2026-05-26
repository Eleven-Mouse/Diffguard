"""Application settings loaded from environment variables."""

from __future__ import annotations

import os


class Settings:
    """Runtime configuration for the agent service."""

    # Java tool server (optional)
    JAVA_TOOL_SERVER_URL: str = os.getenv("JAVA_TOOL_SERVER_URL", "")
    DIFFGUARD_TOOL_SECRET: str | None = os.getenv("DIFFGUARD_TOOL_SECRET")

    # Agent HTTP server
    AGENT_HOST: str = os.getenv("AGENT_HOST", "0.0.0.0")
    AGENT_PORT: int = int(os.getenv("AGENT_PORT", "8000"))
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "info")

    # Chunking / fallback controls
    CHUNK_MAX_FILES: int = int(os.getenv("DIFFGUARD_CHUNK_MAX_FILES", "10"))
    CHUNK_MAX_CHARS: int = int(os.getenv("DIFFGUARD_CHUNK_MAX_CHARS", "60000"))
    CHUNK_MAX_TOKENS: int = int(os.getenv("DIFFGUARD_CHUNK_MAX_TOKENS", "12000"))
    CHUNK_SOFT_TOKENS: int = int(os.getenv("DIFFGUARD_CHUNK_SOFT_TOKENS", "9000"))
    CHUNK_MAX_FAILED_RATIO: float = float(os.getenv("DIFFGUARD_CHUNK_MAX_FAILED_RATIO", "0.5"))

settings = Settings()
