"""DiffGuard Agent Service - LangChain @tool definitions for code analysis.

All tool factories accept a ``tool_client`` (JavaToolClient) and return a
LangChain ``@tool``-decorated async function that communicates with the
Java tool server.
"""

from langchain_core.tools import tool


# ---------------------------------------------------------------------------
# Diff / source-file tools
# ---------------------------------------------------------------------------

def make_diff_context_tool(tool_client):
    @tool
    async def get_diff_context(query: str) -> str:
        """Get diff context. Use 'summary' for an overview, or a file path for that file's diff."""
        resp = await tool_client.get_diff_context(query)
        if resp.success and resp.result:
            return resp.result
        return f"Error getting diff context for '{query}': {resp.error or 'unknown error'}"

    return get_diff_context


def make_file_content_tool(tool_client):
    @tool
    async def get_file_content(file_path: str) -> str:
        """Read the full content of a source file. Use to understand broader context around changed lines."""
        resp = await tool_client.get_file_content(file_path)
        if resp.success and resp.result:
            return resp.result
        return f"Error reading file {file_path}: {resp.error or 'unknown error'}"

    return get_file_content


# ---------------------------------------------------------------------------
# AST / structural analysis tools
# ---------------------------------------------------------------------------

def make_method_definition_tool(tool_client):
    @tool
    async def get_method_definition(file_path: str) -> str:
        """Extract class structure, method signatures, and call edges from a Java file."""
        resp = await tool_client.get_method_definition(file_path)
        if resp.success and resp.result:
            return resp.result
        return f"Error getting method definition for {file_path}: {resp.error or 'unknown error'}"

    return get_method_definition


def make_call_graph_tool(tool_client):
    @tool
    async def get_call_graph(query: str) -> str:
        """Query the code call graph. Formats: 'callers <method>', 'callees <method>', 'impact <Class.method>'."""
        resp = await tool_client.get_call_graph(query)
        if resp.success and resp.result:
            return resp.result
        return f"Error querying call graph '{query}': {resp.error or 'unknown error'}"

    return get_call_graph


# ---------------------------------------------------------------------------
# Dependency / relationship tools
# ---------------------------------------------------------------------------

def make_related_files_tool(tool_client):
    @tool
    async def get_related_files(query: str) -> str:
        """Find files related to a given file or class. Returns dependencies, dependents, and inheritance."""
        resp = await tool_client.get_related_files(query)
        if resp.success and resp.result:
            return resp.result
        return f"Error finding related files for '{query}': {resp.error or 'unknown error'}"

    return get_related_files


# ---------------------------------------------------------------------------
# Semantic search tool
# ---------------------------------------------------------------------------

def make_semantic_search_tool(tool_client):
    @tool
    async def semantic_search(query: str) -> str:
        """Search for code semantically. Returns top-5 ranked code chunks matching the query."""
        resp = await tool_client.semantic_search(query)
        if resp.success and resp.result:
            return resp.result
        return f"Error searching for '{query}': {resp.error or 'unknown error'}"

    return semantic_search
