"""Compatibility entrypoint for legacy `diffguard.main` imports."""

from __future__ import annotations

from diffguard_agent.main import app, main

__all__ = ["app", "main"]


if __name__ == "__main__":
    main()
