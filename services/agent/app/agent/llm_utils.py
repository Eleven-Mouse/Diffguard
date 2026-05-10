"""Shared LLM and prompt utilities."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from langchain_core.language_models.chat_models import BaseChatModel

_PROMPTS_DIR = Path(__file__).parent.parent / "llm" / "prompts"


def create_llm(config: Any) -> BaseChatModel:
    """Create a LangChain ChatModel from the LLM config in the request."""
    llm_cfg = config.llm_config
    if llm_cfg.provider == "claude":
        from langchain_anthropic import ChatAnthropic

        kwargs: dict[str, Any] = {
            "model": llm_cfg.model,
            "max_tokens": llm_cfg.max_tokens,
            "temperature": llm_cfg.temperature,
            "timeout": llm_cfg.timeout_seconds,
        }
        if llm_cfg.api_key:
            kwargs["api_key"] = llm_cfg.api_key
        if llm_cfg.base_url:
            kwargs["anthropic_api_url"] = llm_cfg.base_url
        return ChatAnthropic(**kwargs)
    else:
        from langchain_openai import ChatOpenAI

        kwargs = {
            "model": llm_cfg.model,
            "max_tokens": llm_cfg.max_tokens,
            "temperature": llm_cfg.temperature,
            "timeout": llm_cfg.timeout_seconds,
        }
        if llm_cfg.api_key:
            kwargs["api_key"] = llm_cfg.api_key
        if llm_cfg.base_url:
            kwargs["base_url"] = llm_cfg.base_url
        return ChatOpenAI(**kwargs)


def load_prompt(name: str) -> str:
    """Load a prompt template from the prompts directory."""
    return (_PROMPTS_DIR / name).read_text(encoding="utf-8")
