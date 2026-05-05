"""LangChain tool: read file content via Java tool server."""

from langchain_core.tools import tool


def make_file_content_tool(tool_client):
    @tool
    async def get_file_content(file_path: str) -> str:
        """Read the full content of a source file. Use to understand broader context around changed lines."""
        resp = await tool_client.get_file_content(file_path)
        if resp.success and resp.result:
            return resp.result
        return f"Error reading file {file_path}: {resp.error or 'unknown error'}"

    return get_file_content
