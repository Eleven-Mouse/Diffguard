"""LangChain tool: get method/AST definitions via Java tool server."""

from langchain_core.tools import tool


def make_method_definition_tool(tool_client):
    @tool
    async def get_method_definition(file_path: str) -> str:
        """Extract class structure, method signatures, and call edges from a Java file."""
        resp = await tool_client.get_method_definition(file_path)
        if resp.success and resp.result:
            return resp.result
        return f"Error getting method definition for {file_path}: {resp.error or 'unknown error'}"

    return get_method_definition
