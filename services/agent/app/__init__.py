"""Compatibility package.

Maps ``app.*`` imports to modules under ``diffguard/*`` so legacy import
paths keep working during package layout migration.
"""

from pathlib import Path

__path__ = [str(Path(__file__).resolve().parent.parent / "diffguard")]

