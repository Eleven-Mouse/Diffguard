"""LangChain tool: find related files via Java tool server."""

from langchain_core.tools import tool


def make_related_files_tool(tool_client):
    @tool
    async def get_related_files(query: str) -> str:
        """Find files related to a given file or class. Returns dependencies, dependents, and inheritance."""
        resp = await tool_client.get_related_files(query)
        if resp.success and resp.result:
            return resp.result
        return f"Error finding related files for '{query}': {resp.error or 'unknown error'}"

    return get_related_files
