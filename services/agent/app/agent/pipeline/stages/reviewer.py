"""Reviewer stage - runs parallel domain-specific reviews.

When ``tool_client`` is available, each reviewer runs as a tool-calling
ReAct agent (via LangChain AgentExecutor).  When ``tool_client`` is ``None``
(e.g. GitHub Action mode without the Java Tool Server), reviewers fall back
to a direct structured-LLM call — cheaper and faster, but without the
ability to explore the codebase on its own.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from pydantic import BaseModel, Field

from app.models.schemas import IssuePayload
from app.agent.pipeline.stages.base import PipelineContext, PipelineStage
from app.agent.llm_utils import load_prompt, sanitize_diff_for_prompt

logger = logging.getLogger(__name__)

# JSON 解析 fallback 的 system prompt
_JSON_PARSE_SYSTEM = (
    "You are a JSON parsing expert. Your task is to extract structured JSON from text. "
    "Respond ONLY with valid JSON — no markdown, no code blocks, no explanations. "
    "If the text contains JSON inside code blocks or mixed with other text, "
    "extract just the JSON and output it as plain JSON."
)

# ReAct Agent 的 system prompt 追加（强制输出纯 JSON）
_JSON_OUTPUT_CONSTRAINT = (
    "\n\nIMPORTANT: You must output ONLY pure JSON in the following exact format. "
    "Do not include any text before or after the JSON. "
    'Format: {"summary": "...", "issues": [{"severity": "CRITICAL|WARNING|INFO", '
    '"file": "path", "line": number, "type": "...", "message": "...", '
    '"suggestion": "...", "confidence": 0.0-1.0}]}'
)


class _TargetedReviewResult(BaseModel):
    summary: str = ""
    issues: list[IssuePayload] = Field(default_factory=list)


class ReviewerStage(PipelineStage):
    """Runs multiple domain-specific reviewers in parallel."""

    @property
    def name(self) -> str:
        return "review"

    def __init__(self, reviewers: list[tuple[str, str, str]] | None = None) -> None:
        self._reviewers = reviewers or [
            ("security", "pipeline/security-system.txt", "pipeline/security-user.txt"),
            ("logic", "pipeline/logic-system.txt", "pipeline/logic-user.txt"),
            ("quality", "pipeline/quality-system.txt", "pipeline/quality-user.txt"),
        ]

    async def execute(self, context: PipelineContext) -> PipelineContext:
        logger.info("Pipeline Stage [review]: Running %d reviewers", len(self._reviewers))

        tasks = [
            self._run_reviewer(
                context.llm, name, sys_file, usr_file,
                context.summary, context.diff_text, context.tool_client,
                file_diffs=context.file_diffs or None,
                file_group=context.file_groups.get(name),
            )
            for name, sys_file, usr_file in self._reviewers
        ]

        results = await asyncio.gather(*tasks, return_exceptions=True)

        for (name, _, _), result in zip(self._reviewers, results):
            if isinstance(result, Exception):
                logger.warning("Reviewer '%s' failed: %s", name, result)
                context.review_results[name] = _TargetedReviewResult(
                    summary=f"Reviewer failed: {result}"
                ).model_dump_json()
            else:
                context.review_results[name] = result.model_dump_json()

        logger.info("All reviewers completed: %s", list(context.review_results.keys()))
        return context

    async def _run_reviewer(
        self,
        llm: Any,
        name: str,
        system_prompt_file: str,
        user_prompt_file: str,
        summary: str,
        diff_text: str,
        tool_client: Any,
        file_diffs: dict[str, str] | None = None,
        file_group: list[str] | None = None,
    ) -> _TargetedReviewResult:
        system = load_prompt(system_prompt_file)
        user_tpl = load_prompt(user_prompt_file)

        # Build a domain-filtered diff when per-file data is available.
        if file_diffs and file_group is not None:
            relevant = _build_relevant_diff(file_diffs, file_group)
            # Only use filtered diff if it meaningfully reduces size;
            # otherwise fall back to full diff to avoid losing context.
            if relevant and len(relevant) < len(diff_text) * 0.85:
                effective_diff = relevant
            else:
                effective_diff = diff_text
        else:
            effective_diff = diff_text

        user = user_tpl.replace("{{summary}}", summary).replace("{{diff}}", sanitize_diff_for_prompt(effective_diff))

        if tool_client is not None:
            return await self._run_with_tools(llm, system, user, tool_client)
        return await self._run_direct(llm, system, user)

    async def _run_with_tools(
        self, llm: Any, system: str, user: str, tool_client: Any,
    ) -> _TargetedReviewResult:
        from langchain.agents import AgentExecutor, create_tool_calling_agent
        from langchain_core.prompts import ChatPromptTemplate

        from app.tools.definitions import (
            make_call_graph_tool,
            make_diff_context_tool,
            make_file_content_tool,
            make_method_definition_tool,
            make_related_files_tool,
            make_semantic_search_tool,
        )

        tools = [
            make_file_content_tool(tool_client),
            make_diff_context_tool(tool_client),
            make_method_definition_tool(tool_client),
            make_call_graph_tool(tool_client),
            make_related_files_tool(tool_client),
            make_semantic_search_tool(tool_client),
        ]

        # 追加 JSON 输出约束到 user prompt
        user_with_constraint = user + _JSON_OUTPUT_CONSTRAINT

        prompt = ChatPromptTemplate.from_messages([
            ("system", system),
            ("human", user_with_constraint),
            ("placeholder", "{agent_scratchpad}"),
        ])

        agent = create_tool_calling_agent(llm, tools, prompt)
        executor = AgentExecutor(agent=agent, tools=tools, max_iterations=8, verbose=True)

        raw = await executor.ainvoke({"input": user_with_constraint})
        output_text = raw.get("output", "")

        try:
            return _TargetedReviewResult.model_validate_json(output_text)
        except Exception:
            logger.warning("JSON parse failed, using fallback parser")
            return await self._parse_fallback(llm, output_text)

    async def _run_direct(
        self, llm: Any, system: str, user: str,
    ) -> _TargetedReviewResult:
        from app.agent.llm_utils import invoke_with_retry
        structured_llm = llm.with_structured_output(_TargetedReviewResult)
        return await invoke_with_retry(
            structured_llm,
            [("system", system), ("human", user)]
        )

    async def _parse_fallback(self, llm: Any, output_text: str) -> _TargetedReviewResult:
        """Fallback 解析器：当 JSON 解析失败时的恢复机制"""
        # 先尝试提取 JSON（处理 markdown 代码块等情况）
        from langchain_core.messages import SystemMessage, HumanMessage

        clean_text = self._extract_json_from_text(output_text)
        if clean_text:
            try:
                return _TargetedReviewResult.model_validate_json(clean_text)
            except Exception:
                pass  # 继续用 LLM 解析

        # 使用 LLM 解析，添加 system prompt
        parse_prompt = (
            "Parse the following review output into structured JSON matching this schema:\n"
            '{"summary": string, "issues": [{"severity": "CRITICAL|WARNING|INFO", '
            '"file": string, "line": number, "type": string, '
            '"message": string, "suggestion": string, "confidence": float}]}\n\n'
            "If there are no issues, output: {\"summary\": \"...\", \"issues\": []}\n\n"
            f"Output:\n{output_text[:2000]}"  # 限制输入长度
        )

        structured = llm.with_structured_output(_TargetedReviewResult)
        result = await structured.ainvoke([
            SystemMessage(content=_JSON_PARSE_SYSTEM),
            HumanMessage(content=parse_prompt)
        ])
        return result

    def _extract_json_from_text(self, text: str) -> str | None:
        """从混合文本中提取 JSON"""
        import re
        # 尝试从 code block 中提取
        patterns = [
            r'```(?:json)?\s*(.*?)\s*```',
            r'```\s*(\{.*?\})\s*```',
        ]
        for pattern in patterns:
            match = re.search(pattern, text, re.DOTALL)
            if match:
                return match.group(1)

        # 尝试直接找 JSON 对象
        try:
            import json
            json.loads(text.strip())
            return text.strip()
        except json.JSONDecodeError:
            pass

        # 找花括号包围的 JSON
        brace_count = 0
        start = -1
        for i, c in enumerate(text):
            if c == '{':
                if brace_count == 0:
                    start = i
                brace_count += 1
            elif c == '}':
                brace_count -= 1
                if brace_count == 0 and start >= 0:
                    return text[start:i+1]
        return None


# ---------------------------------------------------------------------------
# Token-optimisation helpers
# ---------------------------------------------------------------------------


def _build_relevant_diff(
    file_diffs: dict[str, str],
    relevant_files: list[str],
) -> str:
    """Concatenate only the diff sections for *relevant_files*.

    Falls back to the full diff if the filtered result would be empty.
    """
    parts: list[str] = []
    for fp in relevant_files:
        section = file_diffs.get(fp)
        if section:
            parts.append(section)
    return "\n".join(parts) if parts else ""
