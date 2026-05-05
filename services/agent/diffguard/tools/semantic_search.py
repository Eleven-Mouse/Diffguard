"""LangChain tool: semantic code search via Java tool server."""

from langchain_core.tools import tool


def make_semantic_search_tool(tool_client):
    @tool
    async def semantic_search(query: str) -> str:
        """Search for code semantically. Returns top-5 ranked code chunks matching the query."""
        resp = await tool_client.semantic_search(query)
        if resp.success and resp.result:
            return resp.result
        return f"Error searching for '{query}': {resp.error or 'unknown error'}"

    return semantic_search
