"""DiffGuard Agent - shared test fixtures."""

import pytest
from unittest.mock import AsyncMock, MagicMock

from app.models.schemas import (
    IssuePayload,
    ReviewRequest,
    ReviewResponse,
    DiffEntry,
    LlmConfig,
    ReviewConfigPayload,
    ReviewMode,
    ToolResponse,
)
from app.agent.base import AgentReviewResult


@pytest.fixture
def sample_issue():
    """A single IssuePayload with explicit values."""
    return IssuePayload(
        severity="WARNING",
        file="src/main.java",
        line=42,
        type="sql_injection",
        message="Potential SQL injection",
        suggestion="Use PreparedStatement",
    )


@pytest.fixture
def sample_issues():
    """A list of three IssuePayload objects with different severities."""
    return [
        IssuePayload(
            severity="WARNING",
            file="src/main.java",
            line=42,
            type="sql_injection",
            message="Potential SQL injection",
            suggestion="Use PreparedStatement",
        ),
        IssuePayload(
            severity="CRITICAL",
            file="src/config.java",
            line=10,
            type="hardcoded_secret",
            message="Hardcoded secret detected",
            suggestion="Use environment variables",
        ),
        IssuePayload(
            severity="INFO",
            file="src/util.java",
            line=5,
            type="naming_convention",
            message="Variable name does not follow convention",
            suggestion="Rename to camelCase",
        ),
    ]


@pytest.fixture
def mock_tool_client():
    """MagicMock with all JavaToolClient methods as AsyncMock."""
    client = MagicMock()
    for method_name in (
        "get_diff_context",
        "get_file_content",
        "get_method_definition",
        "get_call_graph",
        "get_related_files",
        "semantic_search",
    ):
        setattr(client, method_name, AsyncMock(return_value="mock result"))
    return client


@pytest.fixture
def mock_llm():
    """MagicMock standing in for a LangChain chat model."""
    llm = MagicMock()
    llm.ainvoke = AsyncMock()
    llm.astream = MagicMock()
    return llm


@pytest.fixture
def sample_diff_entries():
    """A list of two DiffEntry objects."""
    return [
        DiffEntry(
            file_path="src/main.java",
            content="public class Main { }",
            token_count=10,
        ),
        DiffEntry(
            file_path="src/util.java",
            content="class Util { }",
            token_count=5,
        ),
    ]


@pytest.fixture
def sample_review_request(sample_diff_entries):
    """A ReviewRequest in PIPELINE mode."""
    return ReviewRequest(
        request_id="req-001",
        mode=ReviewMode.PIPELINE,
        project_dir="/tmp/project",
        diff_entries=sample_diff_entries,
        llm_config=LlmConfig(provider="openai", model="gpt-4o"),
        review_config=ReviewConfigPayload(),
    )


@pytest.fixture
def sample_agent_result():
    """An AgentReviewResult with two issues, no criticals."""
    return AgentReviewResult(
        has_critical=False,
        summary="No critical issues found.",
        issues=[
            IssuePayload(
                severity="WARNING",
                file="src/main.java",
                line=42,
                type="sql_injection",
                message="Potential SQL injection",
                suggestion="Use PreparedStatement",
            ),
            IssuePayload(
                severity="INFO",
                file="src/util.java",
                line=5,
                type="naming_convention",
                message="Variable name does not follow convention",
                suggestion="Rename to camelCase",
            ),
        ],
    )
