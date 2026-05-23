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

    # Webhook HMAC secret (optional - enables signature verification)
    WEBHOOK_HMAC_SECRET: str | None = os.getenv("DIFFGUARD_WEBHOOK_HMAC_SECRET")


settings = Settings()