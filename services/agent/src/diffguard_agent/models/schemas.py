"""DiffGuard Agent Service - API request/response schemas."""

from __future__ import annotations

import os
from enum import Enum
from typing import Any, Literal, Optional

from pydantic import BaseModel, Field, model_validator


class ReviewMode(str, Enum):
    PIPELINE = "PIPELINE"
    MULTI_AGENT = "MULTI_AGENT"


class ReviewStatus(str, Enum):
    COMPLETED = "completed"
    FAILED = "failed"


class DiffEntry(BaseModel):
    file_path: str
    content: str
    token_count: int = 0


class LlmConfig(BaseModel):
    provider: Literal["openai", "claude"] = "openai"
    model: str = "gpt-4o"
    api_key: str = Field(default="", exclude=True)
    api_key_env: str = "DIFFGUARD_API_KEY"
    base_url: Optional[str] = None
    max_tokens: int = 16384
    temperature: float = 0.3
    timeout_seconds: int = 300

    @model_validator(mode="after")
    def resolve_api_key(self) -> LlmConfig:
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


class ReviewRequest(BaseModel):
    request_id: str = ""
    task_id: Optional[str] = None
    mode: ReviewMode
    project_dir: str
    diff_entries: list[DiffEntry]
    llm_config: LlmConfig
    review_config: ReviewConfigPayload = Field(default_factory=ReviewConfigPayload)
    tool_server_url: str = "http://localhost:9090"
    allowed_files: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def sync_ids(self) -> ReviewRequest:
        if not self.request_id and self.task_id:
            self.request_id = self.task_id
        if not self.task_id and self.request_id:
            self.task_id = self.request_id
        return self


class IssuePayload(BaseModel):
    severity: str = "INFO"
    file: str = ""
    line: Optional[int] = None
    type: str = ""
    message: str = ""
    suggestion: str = ""
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)
    filter_metadata: dict[str, Any] = Field(default_factory=dict)


class ReviewResponse(BaseModel):
    task_id: Optional[str] = None
    request_id: str
    status: ReviewStatus = ReviewStatus.COMPLETED
    has_critical_flag: bool = False
    issues: list[IssuePayload] = Field(default_factory=list)
    total_tokens_used: int = 0
    review_duration_ms: int = 0
    summary: str = ""
    error: Optional[str] = None

    @model_validator(mode="after")
    def sync_ids(self) -> ReviewResponse:
        if not self.task_id and self.request_id:
            self.task_id = self.request_id
        return self


class ToolSessionRequest(BaseModel):
    session_id: str = ""
    project_dir: str
    diff_entries: list[DiffEntry]
    allowed_files: list[str] = Field(default_factory=list)


class ToolRequest(BaseModel):
    file_path: Optional[str] = None
    query: Optional[str] = None


class ToolResponse(BaseModel):
    success: bool
    result: Optional[str] = None
    error: Optional[str] = None


class HealthResponse(BaseModel):
    status: str = "ok"
    langchain_version: str = ""


class WebhookReviewRequest(BaseModel):
    request_id: str = ""
    repo_full_name: str
    pr_number: int
    project_dir: str = "."
    llm_config: LlmConfig
    review_config: ReviewConfigPayload = Field(default_factory=ReviewConfigPayload)
    tool_server_url: str = "http://localhost:9090"
    github_token_env: str = "GITHUB_TOKEN"
    excluded_dirs: list[str] = Field(default_factory=list)
    head_sha: Optional[str] = None
