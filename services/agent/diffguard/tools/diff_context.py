"""LangChain tool: get diff context via Java tool server."""

from langchain_core.tools import tool


def make_diff_context_tool(tool_client):
    @tool
    async def get_diff_context(query: str) -> str:
        """Get diff context. Use 'summary' for an overview, or a file path for that file's diff."""
        resp = await tool_client.get_diff_context(query)
        if resp.success and resp.result:
            return resp.result
        return f"Error getting diff context for '{query}': {resp.error or 'unknown error'}"

    return get_diff_context
