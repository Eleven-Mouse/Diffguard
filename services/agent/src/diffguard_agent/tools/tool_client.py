"""Async client for Java Tool Server and tool session lifecycle helpers."""

from __future__ import annotations

from typing import Any

import httpx

from diffguard_agent.models.schemas import DiffEntry, ToolResponse


class JavaToolClient:
    """HTTP client wrapper for Java Tool Server endpoints."""

    def __init__(self, base_url: str, session_id: str, tool_secret: str | None = None) -> None:
        self._base_url = base_url.rstrip("/")
        self._session_id = session_id
        self._tool_secret = tool_secret
        self._client = httpx.AsyncClient(timeout=30)

    @property
    def session_id(self) -> str:
        return self._session_id

    @property
    def _headers(self) -> dict[str, str]:
        headers = {"X-Session-Id": self._session_id}
        if self._tool_secret:
            headers["X-Tool-Secret"] = self._tool_secret
        return headers

    async def _post(self, path: str, payload: dict[str, Any] | None = None) -> ToolResponse:
        try:
            resp = await self._client.post(
                f"{self._base_url}{path}",
                headers=self._headers,
                json=payload or {},
            )
            resp.raise_for_status()
            data = resp.json()
            return ToolResponse(
                success=bool(data.get("success")),
                result=data.get("result"),
                error=data.get("error"),
            )
        except Exception as exc:  # pragma: no cover - exercised by tests via generic error path
            return ToolResponse(success=False, error=str(exc))

    async def get_file_content(self, file_path: str) -> ToolResponse:
        return await self._post("/api/v1/tools/file-content", {"file_path": file_path})

    async def get_diff_context(self, query: str) -> ToolResponse:
        return await self._post("/api/v1/tools/diff-context", {"query": query})

    async def get_method_definition(self, file_path: str) -> ToolResponse:
        return await self._post("/api/v1/tools/method-definition", {"file_path": file_path})

    async def get_call_graph(self, query: str) -> ToolResponse:
        return await self._post("/api/v1/tools/call-graph", {"query": query})

    async def get_related_files(self, query: str) -> ToolResponse:
        return await self._post("/api/v1/tools/related-files", {"query": query})

    async def semantic_search(self, query: str) -> ToolResponse:
        return await self._post("/api/v1/tools/semantic-search", {"query": query})

    async def close(self) -> None:
        await self._client.aclose()


async def create_tool_session(
    base_url: str,
    diff_entries: list[DiffEntry],
    project_dir: str,
    allowed_files: list[str],
    tool_secret: str | None = None,
) -> JavaToolClient:
    """Create tool session on Java server and return a bound client."""
    headers: dict[str, str] = {}
    if tool_secret:
        headers["X-Tool-Secret"] = tool_secret

    payload = {
        "project_dir": project_dir,
        "diff_entries": [e.model_dump() for e in diff_entries],
        "allowed_files": allowed_files,
    }

    normalized = base_url.rstrip("/")
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{normalized}/api/v1/tools/session",
            headers=headers,
            json=payload,
        )
        resp.raise_for_status()
        data = resp.json()

    if not data.get("success"):
        raise RuntimeError(f"Failed to create tool session: {data.get('error', 'unknown error')}")

    session_id = data.get("session_id")
    if not session_id:
        raise RuntimeError("Failed to create tool session: missing session_id")

    return JavaToolClient(normalized, str(session_id), tool_secret=tool_secret)


async def destroy_tool_session(client: JavaToolClient) -> None:
    """Delete server-side tool session and always close local HTTP client."""
    try:
        await client._post(f"/api/v1/tools/session/{client.session_id}")
    finally:
        await client.close()
