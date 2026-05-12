"""Shared LLM and prompt utilities."""

from __future__ import annotations

import asyncio
import logging
import random
import time
from pathlib import Path
from typing import Any, Callable, TypeVar

from langchain_core.language_models.chat_models import BaseChatModel

logger = logging.getLogger(__name__)
_PROMPTS_DIR = Path(__file__).parent.parent / "llm" / "prompts"


# ---------------------------------------------------------------------------
# Structured LLM exception classes
# ---------------------------------------------------------------------------


class LLMError(Exception):
    """Base class for all LLM call errors."""


class LLMRateLimitError(LLMError):
    """HTTP 429 — rate limited by the provider."""


class LLMServerError(LLMError):
    """HTTP 5xx — server-side error from the provider."""

    def __init__(self, message: str, status_code: int = 0) -> None:
        super().__init__(message)
        self.status_code = status_code


class LLMTimeoutError(LLMError):
    """Request timed out."""


class LLMClientError(LLMError):
    """Other client-side errors (4xx, parsing, etc)."""


def classify_llm_error(exc: Exception) -> LLMError:
    """Classify a raw exception into a structured LLM error type.

    Inspects the exception class name and message to handle errors from
    different LangChain providers (Anthropic, OpenAI) uniformly.
    """
    msg = str(exc).lower()
    cls_name = type(exc).__name__.lower()

    # Timeout — no retry
    if "timeout" in msg or "timed out" in msg or "timeout" in cls_name:
        return LLMTimeoutError(str(exc))

    # Rate limit
    if ("429" in msg or "rate" in msg or "ratelimit" in cls_name
            or "too_many_requests" in cls_name):
        return LLMRateLimitError(str(exc))

    # Server error
    for code in ("500", "502", "503", "504"):
        if code in msg:
            return LLMServerError(str(exc), status_code=int(code))
    if "server_error" in cls_name or "internal_error" in cls_name:
        return LLMServerError(str(exc))

    return LLMClientError(str(exc))


# ---------------------------------------------------------------------------
# LLM factory
# ---------------------------------------------------------------------------


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


def sanitize_diff_for_prompt(diff_text: str) -> str:
    """Escape XML-like tags in diff content to prevent prompt injection.

    When diff content is wrapped in <diff_input> tags, any closing tag
    like </diff_input> in the diff itself would break the XML boundary.
    This function neutralizes such attempts.
    """
    return diff_text.replace("</diff_input>", "<\\/diff_input>").replace(
        "</summary_input>", "<\\/summary_input>"
    )


# ---------------------------------------------------------------------------
# Retry with structured error classification


# ---------------------------------------------------------------------------

T = TypeVar("T")


async def call_with_retry(
    llm: BaseChatModel,
    func: Callable[..., T],
    max_retries: int = 3,
    base_delay: float = 2.0,
    max_delay: float = 30.0,
    context: str = "LLM call",
) -> T:
    """Retry wrapper with structured error classification and jitter.

    Retry strategy:
        - Rate Limit (429): exponential backoff with jitter, up to 3 retries
        - Server Error (5xx): exponential backoff with jitter
        - Timeout: no retry, immediate failure
        - Other errors: fixed delay, up to 3 retries
    """
    last_error: Exception | None = None

    for attempt in range(max_retries + 1):
        try:
            if asyncio.iscoroutinefunction(func):
                return await func()
            else:
                return func()

        except Exception as e:
            last_error = e
            classified = classify_llm_error(e)

            # Timeout — never retry
            if isinstance(classified, LLMTimeoutError):
                logger.warning("%s timed out, not retrying: %s", context, classified)
                raise classified from e

            # Determine retry eligibility and delay
            if isinstance(classified, LLMRateLimitError):
                if attempt >= max_retries:
                    logger.error("%s rate limited after %d retries", context, max_retries)
                    raise classified from e
                delay = min(base_delay * (2 ** attempt), max_delay)
            elif isinstance(classified, LLMServerError):
                if attempt >= max_retries:
                    logger.error("%s server error after %d retries", context, max_retries)
                    raise classified from e
                delay = min(base_delay * (2 ** attempt), max_delay)
            else:
                if attempt >= max_retries:
                    logger.error("%s failed after %d retries: %s", context, max_retries, classified)
                    raise classified from e
                delay = base_delay

            # Add jitter (±25%) to avoid thundering herd
            jitter = delay * random.uniform(0.75, 1.25)
            logger.warning(
                "%s %s, retry %d/%d after %.1fs: %s",
                context, type(classified).__name__, attempt + 1, max_retries, jitter, classified,
            )
            await asyncio.sleep(jitter)

    raise last_error  # type: ignore[misc]


async def invoke_with_retry(
    llm: BaseChatModel,
    messages: list,
    *,
    max_retries: int = 3,
    base_delay: float = 2.0,
    max_delay: float = 30.0,
) -> Any:
    """Invoke LLM with retry and structured error handling."""
    async def _call():
        return await llm.ainvoke(messages)

    return await call_with_retry(
        llm, _call,
        max_retries=max_retries,
        base_delay=base_delay,
        max_delay=max_delay,
        context=f"LLM invoke (model={llm.model_name if hasattr(llm, 'model_name') else 'unknown'})"
    )
