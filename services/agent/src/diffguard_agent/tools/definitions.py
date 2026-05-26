"""Tool function factories backed by JavaToolClient."""

from __future__ import annotations

from diffguard_agent.tools.tool_client import JavaToolClient


def _format_result(success: bool, result: str | None, error: str | None) -> str:
    if success:
        return result or ""
    return f"Error: {error or 'unknown error'}"


def _bind_query_tool(
    client: JavaToolClient,
    *,
    name: str,
    description: str,
    method_name: str,
):
    async def _tool(query: str) -> str:
        """Bound tool function."""
        method = getattr(client, method_name)
        resp = await method(query)
        return _format_result(resp.success, resp.result, resp.error)

    async def _ainvoke(payload: dict) -> str:
        return await _tool(payload.get("query", ""))

    _tool.__name__ = name
    _tool.__doc__ = description
    _tool.name = name  # type: ignore[attr-defined]
    _tool.ainvoke = _ainvoke  # type: ignore[attr-defined]
    return _tool


def _bind_file_tool(
    client: JavaToolClient,
    *,
    name: str,
    description: str,
    method_name: str,
):
    async def _tool(file_path: str) -> str:
        """Bound tool function."""
        method = getattr(client, method_name)
        resp = await method(file_path)
        return _format_result(resp.success, resp.result, resp.error)

    async def _ainvoke(payload: dict) -> str:
        return await _tool(payload.get("file_path", ""))

    _tool.__name__ = name
    _tool.__doc__ = description
    _tool.name = name  # type: ignore[attr-defined]
    _tool.ainvoke = _ainvoke  # type: ignore[attr-defined]
    return _tool


def make_method_definition_tool(client: JavaToolClient):
    return _bind_file_tool(
        client,
        name="get_method_definition",
        description="Get method definitions found in the target file.",
        method_name="get_method_definition",
    )


def make_call_graph_tool(client: JavaToolClient):
    return _bind_query_tool(
        client,
        name="get_call_graph",
        description="Get call graph snippets related to query.",
        method_name="get_call_graph",
    )


def make_related_files_tool(client: JavaToolClient):
    return _bind_query_tool(
        client,
        name="get_related_files",
        description="Find related files by symbol or filename query.",
        method_name="get_related_files",
    )


def make_semantic_search_tool(client: JavaToolClient):
    return _bind_query_tool(
        client,
        name="semantic_search",
        description="Run semantic code search using query text.",
        method_name="semantic_search",
    )


def make_diff_context_tool(client: JavaToolClient):
    return _bind_query_tool(
        client,
        name="get_diff_context",
        description="Get additional diff context by query string.",
        method_name="get_diff_context",
    )


def make_file_content_tool(client: JavaToolClient):
    return _bind_file_tool(
        client,
        name="get_file_content",
        description="Read current file content from repository path.",
        method_name="get_file_content",
    )
