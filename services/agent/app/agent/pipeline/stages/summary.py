"""Summary stage - analyzes the diff and produces a structured summary."""

from __future__ import annotations

import logging

from pydantic import BaseModel, Field

from app.agent.pipeline.stages.base import PipelineContext, PipelineStage
from app.agent.llm_utils import load_prompt, invoke_with_retry, sanitize_diff_for_prompt

logger = logging.getLogger(__name__)


class _DiffSummary(BaseModel):
    summary: str = ""
    changed_files: list[str] = Field(default_factory=list)
    change_types: list[str] = Field(default_factory=list)
    estimated_risk_level: int = Field(ge=1, le=5, default=3)
    # Maps reviewer name → list of file paths relevant to that reviewer
    file_focus: dict[str, list[str]] = Field(default_factory=dict)


class SummaryStage(PipelineStage):

    @property
    def name(self) -> str:
        return "summary"

    async def execute(self, context: PipelineContext) -> PipelineContext:
        logger.info("Pipeline Stage [summary]: Analyzing diff")
        system = load_prompt("pipeline/diff-summary-system.txt")
        user_tpl = load_prompt("pipeline/diff-summary-user.txt")
        user = user_tpl.replace("{{diff}}", sanitize_diff_for_prompt(context.diff_text))
        if context.historical_context:
            user = user.replace("{{historical_context}}", context.historical_context)
        else:
            user = user.replace("{{historical_context}}", "（无历史审查记录）")

        structured_llm = context.llm.with_structured_output(_DiffSummary)
        result = await invoke_with_retry(
            structured_llm,
            [("system", system), ("human", user)]
        )

        context.summary = result.summary
        context.changed_files = result.changed_files
        context.change_types = result.change_types
        context.estimated_risk_level = result.estimated_risk_level
        context.file_groups = _normalise_file_groups(
            result.file_focus, context.changed_files,
        )

        logger.info("Summary complete: %d files, risk=%d, groups=%s",
                    len(result.changed_files), result.estimated_risk_level,
                    {k: len(v) for k, v in context.file_groups.items()})
        return context


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_REVIEWER_NAMES = ("security", "logic", "quality")


def _normalise_file_groups(
    file_focus: dict[str, list[str]],
    all_files: list[str],
) -> dict[str, list[str]]:
    """Normalise and validate file_focus from LLM output.

    Ensures every reviewer key exists and every changed file appears in
    at least one group.  If the LLM returned empty/invalid file_focus,
    fall back to assigning all files to every reviewer.
    """
    groups: dict[str, list[str]] = {n: [] for n in _REVIEWER_NAMES}

    if not file_focus:
        for name in _REVIEWER_NAMES:
            groups[name] = list(all_files)
        return groups

    # Collect LLM-assigned files
    assigned: set[str] = set()
    for key, paths in file_focus.items():
        key_lower = key.lower()
        # Map LLM output keys to canonical reviewer names
        matched = None
        if "sec" in key_lower:
            matched = "security"
        elif "log" in key_lower:
            matched = "logic"
        elif "qual" in key_lower:
            matched = "quality"
        if matched and paths:
            groups[matched] = [p for p in paths if p in all_files]
            assigned.update(groups[matched])

    # Any unassigned file goes to all reviewers (safe default)
    for f in all_files:
        if f not in assigned:
            for name in _REVIEWER_NAMES:
                groups[name].append(f)

    return groups
