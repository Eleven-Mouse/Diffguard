"""LangChain tool: query the code call graph via Java tool server."""

from langchain_core.tools import tool


def make_call_graph_tool(tool_client):
    @tool
    async def get_call_graph(query: str) -> str:
        """Query the code call graph. Formats: 'callers <method>', 'callees <method>', 'impact <Class.method>'."""
        resp = await tool_client.get_call_graph(query)
        if resp.success and resp.result:
            return resp.result
        return f"Error querying call graph '{query}': {resp.error or 'unknown error'}"

    return get_call_graph
