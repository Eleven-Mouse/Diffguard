"""Strategy config loader - loads strategy configuration from YAML."""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

_CONFIG_DIR = Path(__file__).parent
_CONFIG_FILE = _CONFIG_DIR / "config.yaml"


@dataclass
class CategoryConfig:
    weights: dict[str, float] = field(default_factory=dict)
    focus: list[str] = field(default_factory=list)


@dataclass
class RiskAdjustment:
    security_delta: float = 0.0
    performance_delta: float = 0.0
    focus: list[str] = field(default_factory=list)


@dataclass
class StrategyConfig:
    defaults: dict[str, float] = field(default_factory=lambda: {
        "security": 1.0, "performance": 1.0, "architecture": 1.0,
    })
    categories: dict[str, CategoryConfig] = field(default_factory=dict)
    risk_adjustments: dict[str, dict[str, RiskAdjustment]] = field(default_factory=dict)

    @classmethod
    def load(cls) -> StrategyConfig:
        """Load config from YAML, with fallback to defaults."""
        if not _CONFIG_FILE.exists():
            logger.debug("Strategy config not found at %s, using defaults", _CONFIG_FILE)
            return cls()

        try:
            import yaml
            with open(_CONFIG_FILE, encoding="utf-8") as f:
                raw = yaml.safe_load(f) or {}

            config = cls()

            # Defaults
            if "defaults" in raw and "weights" in raw["defaults"]:
                config.defaults = raw["defaults"]["weights"]

            # Categories
            if "categories" in raw:
                for cat_name, cat_data in raw["categories"].items():
                    config.categories[cat_name] = CategoryConfig(
                        weights=cat_data.get("weights", {}),
                        focus=cat_data.get("focus", []),
                    )

            # Risk adjustments
            if "risk_adjustments" in raw:
                for risk_level, adjustments in raw["risk_adjustments"].items():
                    config.risk_adjustments[risk_level] = {}
                    for adj_name, adj_data in adjustments.items():
                        config.risk_adjustments[risk_level][adj_name] = RiskAdjustment(
                            security_delta=adj_data.get("security_delta", 0.0),
                            performance_delta=adj_data.get("performance_delta", 0.0),
                            focus=adj_data.get("focus", []),
                        )

            logger.debug("Loaded strategy config: %d categories", len(config.categories))
            return config

        except Exception as e:
            logger.warning("Failed to load strategy config: %s, using defaults", e)
            return cls()
