"""DiffGuard Agent Service - API request/response schemas."""

from __future__ import annotations

import os
from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field, model_validator


# --- Enums ---


class ReviewMode(str, Enum):
    PIPELINE = "PIPELINE"


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
    api_key_env: str = "DIFFGUARD_API_KEY"
    base_url: str | None = None
    max_tokens: int = 16384
    temperature: float = 0.3
    timeout_seconds: int = 300

    @model_validator(mode="after")
    def resolve_api_key(self) -> LlmConfig:
        """从环境变量解析 API Key，不在 HTTP 请求中传递明文。"""
        if not self.api_key:
            env_val = os.environ.get(self.api_key_env, "")
            if env_val:
                self.api_key = env_val.strip()
        return self


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


class WebhookReviewRequest(BaseModel):
    """Request from Java gateway: repo + PR number. Python fetches diff itself."""
    request_id: str = ""
    repo_full_name: str
    pr_number: int
    head_sha: str = ""
    github_token_env: str = "DIFFGUARD_GITHUB_TOKEN"
    llm_config: LlmConfig = Field(default_factory=LlmConfig)
    review_config: ReviewConfigPayload = Field(default_factory=ReviewConfigPayload)
    tool_server_url: str = ""
    project_dir: str = ""
    excluded_dirs: list[str] = Field(default_factory=list)


class IssuePayload(BaseModel):
    severity: str = "INFO"
    file: str = ""
    line: int | None = None
    type: str = ""
    message: str = ""
    suggestion: str = ""
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)
    filter_metadata: dict = Field(default_factory=dict)


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
