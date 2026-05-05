"""HTTP client for calling the Java tool server."""

from __future__ import annotations

import logging
from typing import Any

import httpx

from app.models.schemas import DiffEntry, ToolResponse

logger = logging.getLogger(__name__)

_DEFAULT_TIMEOUT = 10.0


class JavaToolClient:
    """Manages sessions and dispatches tool calls to the Java tool server."""

    def __init__(self, base_url: str, session_id: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._session_id = session_id
        self._client = httpx.AsyncClient(timeout=_DEFAULT_TIMEOUT)

    @property
    def session_id(self) -> str:
        return self._session_id

    @property
    def _headers(self) -> dict[str, str]:
        return {"X-Session-Id": self._session_id}

    async def _post(self, path: str, payload: dict[str, Any] | None = None) -> ToolResponse:
        url = f"{self._base_url}{path}"
        try:
            resp = await self._client.post(url, json=payload or {}, headers=self._headers)
            resp.raise_for_status()
            data = resp.json()
            return ToolResponse(**data)
        except httpx.HTTPError as exc:
            logger.warning("Tool call failed %s: %s", url, exc)
            return ToolResponse(success=False, error=str(exc))

    async def close(self) -> None:
        await self._client.aclose()

    # --- Tool methods ---

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


async def create_tool_session(
    base_url: str,
    diff_entries: list[DiffEntry],
    project_dir: str,
    allowed_files: list[str],
) -> JavaToolClient:
    """Create a tool session on the Java side and return a ready JavaToolClient."""
    import uuid

    session_id = str(uuid.uuid4())
    client = JavaToolClient(base_url, session_id)

    resp = await client._post(
        "/api/v1/tools/session",
        {
            "session_id": session_id,
            "project_dir": project_dir,
            "diff_entries": [e.model_dump() for e in diff_entries],
            "allowed_files": allowed_files,
        },
    )
    if not resp.success:
        await client.close()
        raise RuntimeError(f"Failed to create tool session: {resp.error}")

    return client


async def destroy_tool_session(client: JavaToolClient) -> None:
    """Delete the tool session on the Java side and close the HTTP client."""
    try:
        await client._post(f"/api/v1/tools/session/{client.session_id}", {})
    finally:
        await client.close()
