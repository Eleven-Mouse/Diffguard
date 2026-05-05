"""Pipeline DSL - YAML-driven review pipeline configuration.

Allows users to define custom review pipelines instead of the hardcoded
3-stage default. Example configuration::

    # pipeline-config.yaml
    pipeline:
      stages:
        - type: static_rules    # Zero LLM cost pre-check
        - type: summary
        - type: reviewer
          reviewers:
            - name: security
              system_prompt: pipeline/security-system.txt
              user_prompt: pipeline/security-user.txt
            - name: quality
              system_prompt: pipeline/quality-system.txt
              user_prompt: pipeline/quality-user.txt
        - type: aggregation
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from app.agent.pipeline.stages.base import PipelineStage, PipelineContext
from app.agent.pipeline.stages.summary import SummaryStage
from app.agent.pipeline.stages.reviewer import ReviewerStage
from app.agent.pipeline.stages.aggregation import AggregationStage

logger = logging.getLogger(__name__)

_CONFIG_DIR = Path(__file__).parent.parent / "pipeline"


@dataclass
class ReviewerDef:
    """Definition of a single reviewer within the pipeline DSL."""
    name: str
    system_prompt: str
    user_prompt: str


@dataclass
class StageDef:
    """Definition of a pipeline stage from YAML."""
    type: str
    reviewers: list[ReviewerDef] = field(default_factory=list)


def load_pipeline_config(config_path: str | None = None) -> list[StageDef]:
    """Load pipeline configuration from YAML.

    Args:
        config_path: Path to the YAML config file. If None, uses the default.

    Returns:
        List of StageDef objects.
    """
    path = Path(config_path) if config_path else _CONFIG_DIR / "pipeline-config.yaml"

    if not path.exists():
        logger.debug("No pipeline config at %s, using default 3-stage", path)
        return _default_stages()

    try:
        import yaml
        with open(path, encoding="utf-8") as f:
            raw = yaml.safe_load(f) or {}

        stages_raw = raw.get("pipeline", {}).get("stages", [])
        if not stages_raw:
            logger.warning("Empty pipeline config, using default")
            return _default_stages()

        stages = []
        for s in stages_raw:
            stage_type = s.get("type", "")
            reviewers = [
                ReviewerDef(
                    name=r.get("name", ""),
                    system_prompt=r.get("system_prompt", ""),
                    user_prompt=r.get("user_prompt", ""),
                )
                for r in s.get("reviewers", [])
            ]
            stages.append(StageDef(type=stage_type, reviewers=reviewers))

        logger.info("Loaded pipeline config: %d stages from %s", len(stages), path)
        return stages

    except Exception as e:
        logger.warning("Failed to load pipeline config: %s, using default", e)
        return _default_stages()


def build_pipeline_from_config(config: list[StageDef]) -> list[PipelineStage]:
    """Build PipelineStage instances from StageDef configuration.

    Args:
        config: List of StageDef objects from load_pipeline_config().

    Returns:
        List of PipelineStage instances ready for execution.
    """
    stages: list[PipelineStage] = []

    for stage_def in config:
        if stage_def.type == "summary":
            stages.append(SummaryStage())

        elif stage_def.type == "reviewer":
            if stage_def.reviewers:
                reviewers = [
                    (r.name, r.system_prompt, r.user_prompt)
                    for r in stage_def.reviewers
                ]
                stages.append(ReviewerStage(reviewers=reviewers))
            else:
                stages.append(ReviewerStage())  # default security/logic/quality

        elif stage_def.type == "aggregation":
            stages.append(AggregationStage())

        elif stage_def.type == "static_rules":
            from app.agent.pipeline.stages.static_rules import StaticRulesStage
            stages.append(StaticRulesStage())

        else:
            logger.warning("Unknown pipeline stage type: %s, skipping", stage_def.type)

    if not stages:
        logger.warning("No valid stages in config, falling back to default")
        from app.agent.pipeline_orchestrator import build_default_pipeline
        return build_default_pipeline()

    return stages


def _default_stages() -> list[StageDef]:
    """Default 3-stage pipeline definition."""
    return [
        StageDef(type="summary"),
        StageDef(
            type="reviewer",
            reviewers=[
                ReviewerDef(name="security", system_prompt="pipeline/security-system.txt", user_prompt="pipeline/security-user.txt"),
                ReviewerDef(name="logic", system_prompt="pipeline/logic-system.txt", user_prompt="pipeline/logic-user.txt"),
                ReviewerDef(name="quality", system_prompt="pipeline/quality-system.txt", user_prompt="pipeline/quality-user.txt"),
            ],
        ),
        StageDef(type="aggregation"),
    ]
