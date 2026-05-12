"""Tests for app.tools.tool_client - JavaToolClient and session management."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.models.schemas import DiffEntry, ToolResponse
from app.tools.tool_client import (
    JavaToolClient,
    create_tool_session,
    destroy_tool_session,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_client(base_url="http://localhost:9090", session_id="sess-123", tool_secret=None):
    """Create a JavaToolClient with a mocked httpx.AsyncClient."""
    client = JavaToolClient(base_url, session_id, tool_secret)
    return client


def _mock_httpx_response(json_data, status_code=200):
    """Create a mock httpx.Response."""
    resp = MagicMock()
    resp.status_code = status_code
    resp.json.return_value = json_data
    resp.raise_for_status = MagicMock()
    return resp


# ---------------------------------------------------------------------------
# __init__ and properties
# ---------------------------------------------------------------------------


class TestJavaToolClientInit:

    def test_init_stores_base_url(self):
        client = _make_client("http://example.com:8080", "sid")
        assert client._base_url == "http://example.com:8080"

    def test_init_strips_trailing_slash(self):
        client = _make_client("http://example.com:8080/", "sid")
        assert client._base_url == "http://example.com:8080"

    def test_init_stores_session_id(self):
        client = _make_client("http://localhost:9090", "sess-abc")
        assert client._session_id == "sess-abc"

    def test_session_id_property(self):
        client = _make_client("http://localhost:9090", "sess-xyz")
        assert client.session_id == "sess-xyz"

    def test_init_stores_tool_secret(self):
        client = _make_client("http://localhost:9090", "sid", tool_secret="secret123")
        assert client._tool_secret == "secret123"

    def test_init_no_tool_secret(self):
        client = _make_client("http://localhost:9090", "sid")
        assert client._tool_secret is None

    def test_init_creates_httpx_client(self):
        client = _make_client()
        assert client._client is not None


# ---------------------------------------------------------------------------
# _headers
# ---------------------------------------------------------------------------


class TestHeaders:

    def test_headers_includes_session_id(self):
        client = _make_client("http://localhost:9090", "sess-123")
        headers = client._headers
        assert headers["X-Session-Id"] == "sess-123"

    def test_headers_with_tool_secret(self):
        client = _make_client("http://localhost:9090", "sess-123", tool_secret="my-secret")
        headers = client._headers
        assert headers["X-Tool-Secret"] == "my-secret"

    def test_headers_without_tool_secret(self):
        client = _make_client("http://localhost:9090", "sess-123")
        headers = client._headers
        assert "X-Tool-Secret" not in headers


# ---------------------------------------------------------------------------
# _post (tool response parsing)
# ---------------------------------------------------------------------------


class TestPostMethod:

    async def test_post_success_returns_tool_response(self):
        client = _make_client()
        mock_resp = _mock_httpx_response({"success": True, "result": "data"})
        client._client.post = AsyncMock(return_value=mock_resp)

        result = await client._post("/api/v1/tools/test", {"key": "val"})
        assert isinstance(result, ToolResponse)
        assert result.success is True
        assert result.result == "data"

    async def test_post_error_returns_failed_tool_response(self):
        import httpx

        client = _make_client()
        client._client.post = AsyncMock(side_effect=httpx.HTTPError("connection refused"))

        result = await client._post("/api/v1/tools/test")
        assert isinstance(result, ToolResponse)
        assert result.success is False
        assert "connection refused" in result.error

    async def test_post_sends_correct_headers(self):
        client = _make_client("http://localhost:9090", "sess-abc", tool_secret="sec")
        mock_resp = _mock_httpx_response({"success": True, "result": "ok"})
        client._client.post = AsyncMock(return_value=mock_resp)

        await client._post("/api/v1/tools/test", {"q": "1"})

        call_args = client._client.post.call_args
        headers = call_args.kwargs.get("headers") or call_args[1].get("headers")
        assert headers["X-Session-Id"] == "sess-abc"
        assert headers["X-Tool-Secret"] == "sec"

    async def test_post_constructs_correct_url(self):
        client = _make_client("http://localhost:9090", "sess-1")
        mock_resp = _mock_httpx_response({"success": True})
        client._client.post = AsyncMock(return_value=mock_resp)

        await client._post("/api/v1/tools/file-content", {"file_path": "a.java"})

        call_args = client._client.post.call_args
        url = call_args.args[0] if call_args.args else call_args[0][0]
        assert url == "http://localhost:9090/api/v1/tools/file-content"


# ---------------------------------------------------------------------------
# Tool methods dispatch correctly
# ---------------------------------------------------------------------------


class TestToolMethods:

    async def test_get_file_content_endpoint(self):
        client = _make_client()
        mock_resp = _mock_httpx_response({"success": True, "result": "content"})
        client._client.post = AsyncMock(return_value=mock_resp)

        await client.get_file_content("Main.java")
        url = client._client.post.call_args.args[0]
        assert "/api/v1/tools/file-content" in url
        payload = client._client.post.call_args.kwargs.get("json") or client._client.post.call_args[1].get("json")
        assert payload["file_path"] == "Main.java"

    async def test_get_diff_context_endpoint(self):
        client = _make_client()
        mock_resp = _mock_httpx_response({"success": True, "result": "diff"})
        client._client.post = AsyncMock(return_value=mock_resp)

        await client.get_diff_context("summary")
        url = client._client.post.call_args.args[0]
        assert "/api/v1/tools/diff-context" in url
        payload = client._client.post.call_args.kwargs.get("json") or client._client.post.call_args[1].get("json")
        assert payload["query"] == "summary"

    async def test_get_method_definition_endpoint(self):
        client = _make_client()
        mock_resp = _mock_httpx_response({"success": True, "result": "methods"})
        client._client.post = AsyncMock(return_value=mock_resp)

        await client.get_method_definition("Service.java")
        url = client._client.post.call_args.args[0]
        assert "/api/v1/tools/method-definition" in url

    async def test_get_call_graph_endpoint(self):
        client = _make_client()
        mock_resp = _mock_httpx_response({"success": True, "result": "graph"})
        client._client.post = AsyncMock(return_value=mock_resp)

        await client.get_call_graph("callers Main.run")
        url = client._client.post.call_args.args[0]
        assert "/api/v1/tools/call-graph" in url

    async def test_get_related_files_endpoint(self):
        client = _make_client()
        mock_resp = _mock_httpx_response({"success": True, "result": "related"})
        client._client.post = AsyncMock(return_value=mock_resp)

        await client.get_related_files("Service.java")
        url = client._client.post.call_args.args[0]
        assert "/api/v1/tools/related-files" in url

    async def test_semantic_search_endpoint(self):
        client = _make_client()
        mock_resp = _mock_httpx_response({"success": True, "result": "search"})
        client._client.post = AsyncMock(return_value=mock_resp)

        await client.semantic_search("auth logic")
        url = client._client.post.call_args.args[0]
        assert "/api/v1/tools/semantic-search" in url


# ---------------------------------------------------------------------------
# close
# ---------------------------------------------------------------------------


class TestClose:

    async def test_close_calls_aclose(self):
        client = _make_client()
        client._client.aclose = AsyncMock()
        await client.close()
        client._client.aclose.assert_awaited_once()


# ---------------------------------------------------------------------------
# create_tool_session
# ---------------------------------------------------------------------------


class TestCreateToolSession:

    @patch("app.tools.tool_client.httpx.AsyncClient")
    async def test_create_session_sends_correct_post(self, MockAsyncClient):
        mock_http = AsyncMock()
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"success": True, "session_id": "new-sess-1"}
        mock_resp.raise_for_status = MagicMock()
        mock_http.post = AsyncMock(return_value=mock_resp)
        mock_http.__aenter__ = AsyncMock(return_value=mock_http)
        mock_http.__aexit__ = AsyncMock(return_value=False)
        MockAsyncClient.return_value = mock_http

        entries = [DiffEntry(file_path="a.java", content="diff", token_count=5)]
        client = await create_tool_session(
            "http://localhost:9090",
            entries,
            "/project",
            ["a.java"],
        )

        assert isinstance(client, JavaToolClient)
        assert client.session_id == "new-sess-1"
        assert client._base_url == "http://localhost:9090"

    @patch("app.tools.tool_client.httpx.AsyncClient")
    async def test_create_session_sends_correct_payload(self, MockAsyncClient):
        mock_http = AsyncMock()
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"success": True, "session_id": "sess-x"}
        mock_resp.raise_for_status = MagicMock()
        mock_http.post = AsyncMock(return_value=mock_resp)
        mock_http.__aenter__ = AsyncMock(return_value=mock_http)
        mock_http.__aexit__ = AsyncMock(return_value=False)
        MockAsyncClient.return_value = mock_http

        entries = [DiffEntry(file_path="a.java", content="diff", token_count=5)]
        await create_tool_session(
            "http://localhost:9090",
            entries,
            "/project",
            ["a.java"],
        )

        post_call = mock_http.post.call_args
        payload = post_call.kwargs.get("json") or post_call[1].get("json")
        assert payload["project_dir"] == "/project"
        assert len(payload["diff_entries"]) == 1
        assert payload["diff_entries"][0]["file_path"] == "a.java"
        assert payload["allowed_files"] == ["a.java"]

    @patch("app.tools.tool_client.httpx.AsyncClient")
    async def test_create_session_with_tool_secret(self, MockAsyncClient):
        mock_http = AsyncMock()
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"success": True, "session_id": "sess-y"}
        mock_resp.raise_for_status = MagicMock()
        mock_http.post = AsyncMock(return_value=mock_resp)
        mock_http.__aenter__ = AsyncMock(return_value=mock_http)
        mock_http.__aexit__ = AsyncMock(return_value=False)
        MockAsyncClient.return_value = mock_http

        entries = [DiffEntry(file_path="a.java", content="diff", token_count=5)]
        client = await create_tool_session(
            "http://localhost:9090",
            entries,
            "/project",
            ["a.java"],
            tool_secret="my-secret",
        )

        assert client._tool_secret == "my-secret"
        post_call = mock_http.post.call_args
        headers = post_call.kwargs.get("headers") or post_call[1].get("headers")
        assert headers["X-Tool-Secret"] == "my-secret"

    @patch("app.tools.tool_client.httpx.AsyncClient")
    async def test_create_session_raises_on_failure(self, MockAsyncClient):
        mock_http = AsyncMock()
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"success": False, "error": "bad request"}
        mock_resp.raise_for_status = MagicMock()
        mock_http.post = AsyncMock(return_value=mock_resp)
        mock_http.__aenter__ = AsyncMock(return_value=mock_http)
        mock_http.__aexit__ = AsyncMock(return_value=False)
        MockAsyncClient.return_value = mock_http

        entries = [DiffEntry(file_path="a.java", content="diff", token_count=5)]
        with pytest.raises(RuntimeError, match="Failed to create tool session"):
            await create_tool_session(
                "http://localhost:9090",
                entries,
                "/project",
                ["a.java"],
            )


# ---------------------------------------------------------------------------
# destroy_tool_session
# ---------------------------------------------------------------------------


class TestDestroyToolSession:

    async def test_destroy_calls_close_and_post(self):
        client = _make_client("http://localhost:9090", "sess-del")
        mock_resp = _mock_httpx_response({"success": True})
        client._client.post = AsyncMock(return_value=mock_resp)
        client._client.aclose = AsyncMock()

        await destroy_tool_session(client)

        # Should call _post which calls client._client.post
        client._client.post.assert_awaited()
        client._client.aclose.assert_awaited_once()

    async def test_destroy_closes_even_on_post_error(self):
        import httpx

        client = _make_client("http://localhost:9090", "sess-del")
        client._client.post = AsyncMock(side_effect=httpx.HTTPError("fail"))
        client._client.aclose = AsyncMock()

        await destroy_tool_session(client)

        # close() should still be called (finally block)
        client._client.aclose.assert_awaited_once()
