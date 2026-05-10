"""Tests for app.agent.llm_utils - create_llm and load_prompt."""

from unittest.mock import MagicMock, patch

import pytest

from app.agent.llm_utils import create_llm, load_prompt


def _make_config(provider="openai", model="gpt-4o", api_key="sk-test",
                 base_url=None, max_tokens=1024, temperature=0.5,
                 timeout_seconds=60):
    """Build a lightweight config mock that mirrors LlmConfig access patterns."""
    cfg = MagicMock()
    cfg.llm_config.provider = provider
    cfg.llm_config.model = model
    cfg.llm_config.api_key = api_key
    cfg.llm_config.base_url = base_url
    cfg.llm_config.max_tokens = max_tokens
    cfg.llm_config.temperature = temperature
    cfg.llm_config.timeout_seconds = timeout_seconds
    return cfg


# ---------------------------------------------------------------------------
# create_llm - OpenAI provider
# ---------------------------------------------------------------------------


class TestCreateLlmOpenai:

    @patch("app.agent.llm_utils.ChatOpenAI", create=True)
    def test_creates_chat_openai(self, mock_cls):
        """Patch the imported name inside the llm_utils module."""
        # The function does `from langchain_openai import ChatOpenAI`
        # so we patch the module-level name after import.
        with patch.dict("sys.modules", {"langchain_openai": MagicMock(ChatOpenAI=mock_cls)}):
            # Re-import path: the module already has the name cached,
            # so we patch it directly on the module.
            pass
        # Simpler: patch the module attribute directly
        import app.agent.llm_utils as mod

        mock_openai_cls = MagicMock(return_value="openai_llm")
        with patch.object(mod, "ChatOpenAI", mock_openai_cls, create=True):
            # Actually the function does a local import, so patch at import level
            pass

    def test_openai_provider(self):
        """create_llm with provider=openai should call ChatOpenAI."""
        mock_openai_cls = MagicMock(return_value="openai_llm_instance")
        with patch.dict("sys.modules", {
            "langchain_openai": MagicMock(ChatOpenAI=mock_openai_cls),
        }):
            # Force re-import of the module to pick up patched sys.modules
            # Actually, create_llm does a local `from langchain_openai import ChatOpenAI`
            # which will use the cached module. We need a different approach.
            pass
        # The function does `from langchain_openai import ChatOpenAI` inside the else branch.
        # We patch the langchain_openai module so the local import resolves to our mock.
        mock_module = MagicMock()
        mock_openai_cls = MagicMock(return_value="openai_llm_instance")
        mock_module.ChatOpenAI = mock_openai_cls
        with patch.dict("sys.modules", {"langchain_openai": mock_module}):
            config = _make_config(provider="openai")
            result = create_llm(config)
            assert result == "openai_llm_instance"
            mock_openai_cls.assert_called_once()

    def test_openai_passes_api_key(self):
        mock_module = MagicMock()
        mock_cls = MagicMock(return_value="llm")
        mock_module.ChatOpenAI = mock_cls
        with patch.dict("sys.modules", {"langchain_openai": mock_module}):
            config = _make_config(provider="openai", api_key="sk-secret")
            create_llm(config)
            kwargs = mock_cls.call_args[1]
            assert kwargs["api_key"] == "sk-secret"

    def test_openai_passes_model(self):
        mock_module = MagicMock()
        mock_cls = MagicMock(return_value="llm")
        mock_module.ChatOpenAI = mock_cls
        with patch.dict("sys.modules", {"langchain_openai": mock_module}):
            config = _make_config(provider="openai", model="gpt-4o-mini")
            create_llm(config)
            kwargs = mock_cls.call_args[1]
            assert kwargs["model"] == "gpt-4o-mini"

    def test_openai_with_base_url(self):
        mock_module = MagicMock()
        mock_cls = MagicMock(return_value="llm")
        mock_module.ChatOpenAI = mock_cls
        with patch.dict("sys.modules", {"langchain_openai": mock_module}):
            config = _make_config(provider="openai", base_url="http://custom:8080")
            create_llm(config)
            kwargs = mock_cls.call_args[1]
            assert kwargs["base_url"] == "http://custom:8080"

    def test_openai_temperature_and_max_tokens(self):
        mock_module = MagicMock()
        mock_cls = MagicMock(return_value="llm")
        mock_module.ChatOpenAI = mock_cls
        with patch.dict("sys.modules", {"langchain_openai": mock_module}):
            config = _make_config(
                provider="openai",
                temperature=0.7,
                max_tokens=2048,
            )
            create_llm(config)
            kwargs = mock_cls.call_args[1]
            assert kwargs["temperature"] == 0.7
            assert kwargs["max_tokens"] == 2048


# ---------------------------------------------------------------------------
# create_llm - Claude provider
# ---------------------------------------------------------------------------


class TestCreateLlmClaude:

    def test_claude_provider(self):
        mock_module = MagicMock()
        mock_cls = MagicMock(return_value="claude_llm_instance")
        mock_module.ChatAnthropic = mock_cls
        with patch.dict("sys.modules", {"langchain_anthropic": mock_module}):
            config = _make_config(provider="claude", model="claude-3-sonnet")
            result = create_llm(config)
            assert result == "claude_llm_instance"
            mock_cls.assert_called_once()

    def test_claude_passes_api_key(self):
        mock_module = MagicMock()
        mock_cls = MagicMock(return_value="llm")
        mock_module.ChatAnthropic = mock_cls
        with patch.dict("sys.modules", {"langchain_anthropic": mock_module}):
            config = _make_config(provider="claude", api_key="sk-ant-test")
            create_llm(config)
            kwargs = mock_cls.call_args[1]
            assert kwargs["api_key"] == "sk-ant-test"

    def test_claude_with_base_url(self):
        mock_module = MagicMock()
        mock_cls = MagicMock(return_value="llm")
        mock_module.ChatAnthropic = mock_cls
        with patch.dict("sys.modules", {"langchain_anthropic": mock_module}):
            config = _make_config(provider="claude", base_url="http://custom:9090")
            create_llm(config)
            kwargs = mock_cls.call_args[1]
            assert kwargs["anthropic_api_url"] == "http://custom:9090"


# ---------------------------------------------------------------------------
# load_prompt
# ---------------------------------------------------------------------------


class TestLoadPrompt:

    def test_reads_existing_file(self):
        """react-user.txt exists in app/prompts/."""
        content = load_prompt("react-user.txt")
        assert isinstance(content, str)
        assert len(content) > 0

    def test_raises_for_missing_file(self):
        with pytest.raises(FileNotFoundError):
            load_prompt("nonexistent_prompt_file.txt")
