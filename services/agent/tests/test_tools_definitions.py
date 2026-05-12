"""Tests for app.tools.definitions - LangChain @tool factory functions."""

import inspect

import pytest
from unittest.mock import AsyncMock, MagicMock

from app.tools.definitions import (
    make_call_graph_tool,
    make_diff_context_tool,
    make_file_content_tool,
    make_method_definition_tool,
    make_related_files_tool,
    make_semantic_search_tool,
)
from app.models.schemas import ToolResponse


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


def _make_mock_tool_client():
    """Build a mock tool_client whose methods return successful ToolResponse."""
    client = MagicMock()
    client.get_diff_context = AsyncMock(
        return_value=ToolResponse(success=True, result="diff-context-result")
    )
    client.get_file_content = AsyncMock(
        return_value=ToolResponse(success=True, result="file-content-result")
    )
    client.get_method_definition = AsyncMock(
        return_value=ToolResponse(success=True, result="method-def-result")
    )
    client.get_call_graph = AsyncMock(
        return_value=ToolResponse(success=True, result="call-graph-result")
    )
    client.get_related_files = AsyncMock(
        return_value=ToolResponse(success=True, result="related-files-result")
    )
    client.semantic_search = AsyncMock(
        return_value=ToolResponse(success=True, result="semantic-search-result")
    )
    return client


def _make_error_mock_tool_client():
    """Build a mock tool_client whose methods return failed ToolResponse."""
    client = MagicMock()
    client.get_diff_context = AsyncMock(
        return_value=ToolResponse(success=False, error="not found")
    )
    client.get_file_content = AsyncMock(
        return_value=ToolResponse(success=False, error="file missing")
    )
    client.get_method_definition = AsyncMock(
        return_value=ToolResponse(success=False, error="parse error")
    )
    client.get_call_graph = AsyncMock(
        return_value=ToolResponse(success=False, error="no graph")
    )
    client.get_related_files = AsyncMock(
        return_value=ToolResponse(success=False, error="no relations")
    )
    client.semantic_search = AsyncMock(
        return_value=ToolResponse(success=False, error="index empty")
    )
    return client


@pytest.fixture
def mock_client():
    return _make_mock_tool_client()


@pytest.fixture
def error_client():
    return _make_error_mock_tool_client()


# ---------------------------------------------------------------------------
# Factory return type tests
# ---------------------------------------------------------------------------


class TestFactoryReturnType:

    @pytest.mark.parametrize("factory", [
        make_diff_context_tool,
        make_file_content_tool,
        make_method_definition_tool,
        make_call_graph_tool,
        make_related_files_tool,
        make_semantic_search_tool,
    ], ids=[
        "diff_context", "file_content", "method_definition",
        "call_graph", "related_files", "semantic_search",
    ])
    def test_factory_returns_callable(self, factory, mock_client):
        tool_fn = factory(mock_client)
        assert callable(tool_fn)

    @pytest.mark.parametrize("factory", [
        make_diff_context_tool,
        make_file_content_tool,
        make_method_definition_tool,
        make_call_graph_tool,
        make_related_files_tool,
        make_semantic_search_tool,
    ], ids=[
        "diff_context", "file_content", "method_definition",
        "call_graph", "related_files", "semantic_search",
    ])
    def test_factory_returns_async_function(self, factory, mock_client):
        tool_fn = factory(mock_client)
        assert inspect.iscoroutinefunction(tool_fn)


# ---------------------------------------------------------------------------
# Docstring tests
# ---------------------------------------------------------------------------


class TestToolDocstrings:

    @pytest.mark.parametrize("factory", [
        make_diff_context_tool,
        make_file_content_tool,
        make_method_definition_tool,
        make_call_graph_tool,
        make_related_files_tool,
        make_semantic_search_tool,
    ], ids=[
        "diff_context", "file_content", "method_definition",
        "call_graph", "related_files", "semantic_search",
    ])
    def test_tool_has_docstring(self, factory, mock_client):
        tool_fn = factory(mock_client)
        assert tool_fn.__doc__ is not None
        assert len(tool_fn.__doc__) > 0


# ---------------------------------------------------------------------------
# Correct tool_client method called
# ---------------------------------------------------------------------------


class TestToolClientMethodDispatch:

    async def test_diff_context_calls_get_diff_context(self, mock_client):
        tool_fn = make_diff_context_tool(mock_client)
        result = await tool_fn.ainvoke({"query": "summary"})
        mock_client.get_diff_context.assert_awaited_once_with("summary")
        assert "diff-context-result" in result

    async def test_file_content_calls_get_file_content(self, mock_client):
        tool_fn = make_file_content_tool(mock_client)
        result = await tool_fn.ainvoke({"file_path": "src/Main.java"})
        mock_client.get_file_content.assert_awaited_once_with("src/Main.java")
        assert "file-content-result" in result

    async def test_method_definition_calls_get_method_definition(self, mock_client):
        tool_fn = make_method_definition_tool(mock_client)
        result = await tool_fn.ainvoke({"file_path": "src/Main.java"})
        mock_client.get_method_definition.assert_awaited_once_with("src/Main.java")
        assert "method-def-result" in result

    async def test_call_graph_calls_get_call_graph(self, mock_client):
        tool_fn = make_call_graph_tool(mock_client)
        result = await tool_fn.ainvoke({"query": "callers UserService.findById"})
        mock_client.get_call_graph.assert_awaited_once_with("callers UserService.findById")
        assert "call-graph-result" in result

    async def test_related_files_calls_get_related_files(self, mock_client):
        tool_fn = make_related_files_tool(mock_client)
        result = await tool_fn.ainvoke({"query": "UserService.java"})
        mock_client.get_related_files.assert_awaited_once_with("UserService.java")
        assert "related-files-result" in result

    async def test_semantic_search_calls_semantic_search(self, mock_client):
        tool_fn = make_semantic_search_tool(mock_client)
        result = await tool_fn.ainvoke({"query": "authentication logic"})
        mock_client.semantic_search.assert_awaited_once_with("authentication logic")
        assert "semantic-search-result" in result


# ---------------------------------------------------------------------------
# Error path tests
# ---------------------------------------------------------------------------


class TestToolErrorHandling:

    async def test_diff_context_error_returns_error_string(self, error_client):
        tool_fn = make_diff_context_tool(error_client)
        result = await tool_fn.ainvoke({"query": "summary"})
        assert "Error" in result
        assert "not found" in result

    async def test_file_content_error_returns_error_string(self, error_client):
        tool_fn = make_file_content_tool(error_client)
        result = await tool_fn.ainvoke({"file_path": "missing.java"})
        assert "Error" in result
        assert "file missing" in result

    async def test_method_definition_error_returns_error_string(self, error_client):
        tool_fn = make_method_definition_tool(error_client)
        result = await tool_fn.ainvoke({"file_path": "bad.java"})
        assert "Error" in result

    async def test_call_graph_error_returns_error_string(self, error_client):
        tool_fn = make_call_graph_tool(error_client)
        result = await tool_fn.ainvoke({"query": "bad query"})
        assert "Error" in result

    async def test_related_files_error_returns_error_string(self, error_client):
        tool_fn = make_related_files_tool(error_client)
        result = await tool_fn.ainvoke({"query": "missing.java"})
        assert "Error" in result

    async def test_semantic_search_error_returns_error_string(self, error_client):
        tool_fn = make_semantic_search_tool(error_client)
        result = await tool_fn.ainvoke({"query": "nothing"})
        assert "Error" in result


# ---------------------------------------------------------------------------
# Tool metadata (LangChain tool integration)
# ---------------------------------------------------------------------------


class TestToolMetadata:

    @pytest.mark.parametrize("factory,name", [
        (make_diff_context_tool, "get_diff_context"),
        (make_file_content_tool, "get_file_content"),
        (make_method_definition_tool, "get_method_definition"),
        (make_call_graph_tool, "get_call_graph"),
        (make_related_files_tool, "get_related_files"),
        (make_semantic_search_tool, "semantic_search"),
    ], ids=[
        "diff_context", "file_content", "method_definition",
        "call_graph", "related_files", "semantic_search",
    ])
    def test_tool_has_name_attribute(self, factory, name, mock_client):
        tool_fn = factory(mock_client)
        # LangChain @tool sets .name on the function
        assert hasattr(tool_fn, "name")
        assert tool_fn.name == name
