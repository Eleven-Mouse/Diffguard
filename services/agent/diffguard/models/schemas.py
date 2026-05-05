"""DiffGuard Agent Service - API request/response schemas."""

from __future__ import annotations

from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field


# --- Enums ---


class ReviewMode(str, Enum):
    PIPELINE = "PIPELINE"
    MULTI_AGENT = "MULTI_AGENT"


class ReviewStatus(str, Enum):
    COMPLETED = "completed"
    FAILED = "failed"


# --- Diff / Config models ---


class DiffEntry(BaseModel):
    file_path: str
    content: str
    token_count: int = 0


class LlmConfig(BaseModel):
    provider: Literal["openai", "claude"] = "openai"
    model: str = "gpt-4o"
    api_key: str = ""
    base_url: str | None = None
    max_tokens: int = 16384
    temperature: float = 0.3
    timeout_seconds: int = 300


class ReviewConfigPayload(BaseModel):
    language: str = "zh"
    rules_enabled: list[str] = Field(
        default_factory=lambda: ["security", "bug-risk", "code-style", "performance"]
    )


# --- Request / Response ---


class ReviewRequest(BaseModel):
    request_id: str
    mode: ReviewMode
    project_dir: str
    diff_entries: list[DiffEntry]
    llm_config: LlmConfig
    review_config: ReviewConfigPayload = Field(default_factory=ReviewConfigPayload)
    tool_server_url: str = "http://localhost:9090"
    allowed_files: list[str] = Field(default_factory=list)


class IssuePayload(BaseModel):
    severity: str = "INFO"
    file: str = ""
    line: int | None = None
    type: str = ""
    message: str = ""
    suggestion: str = ""


class ReviewResponse(BaseModel):
    request_id: str
    status: ReviewStatus = ReviewStatus.COMPLETED
    has_critical_flag: bool = False
    issues: list[IssuePayload] = Field(default_factory=list)
    total_tokens_used: int = 0
    review_duration_ms: int = 0
    summary: str = ""
    error: str | None = None


# --- Tool server models ---


class ToolSessionRequest(BaseModel):
    session_id: str
    project_dir: str
    diff_entries: list[DiffEntry]
    allowed_files: list[str] = Field(default_factory=list)


class ToolRequest(BaseModel):
    file_path: str | None = None
    query: str | None = None


class ToolResponse(BaseModel):
    success: bool
    result: str | None = None
    error: str | None = None


# --- Health ---


class HealthResponse(BaseModel):
    status: str = "ok"
    langchain_version: str = ""
