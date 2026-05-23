# Python Coding Standards — DiffGuard Agent

## Project Layout

```
services/agent/
  src/diffguard_agent/     # Source code (src-layout)
  tests/                   # Test files
  config/                  # Configuration files (YAML)
  pyproject.toml           # Build configuration (hatchling)
```

## Import Conventions

All internal imports use the package name `diffguard_agent`:
```python
from diffguard_agent.agent.pipeline_orchestrator import PipelineOrchestrator
from diffguard_agent.models.schemas import ReviewRequest
from diffguard_agent.utils.diff_utils import split_diff
```

## Naming Conventions

- Modules: snake_case (`pipeline_orchestrator.py`, `diff_utils.py`)
- Classes: PascalCase (`PipelineOrchestrator`, `DiffLineMapper`)
- Functions: snake_case (`create_llm`, `invoke_with_retry`)
- Constants: UPPER_SNAKE_CASE (`MAX_FILES_PER_CHUNK`)
- Config files: kebab-case (`pipeline-config.yaml`, `false-positive-rules.yaml`)

## Testing

- pytest with pytest-asyncio (`asyncio_mode = "auto"`)
- Test file naming: `test_<module>.py` (`test_pipeline_stages.py`)
- Fixtures in `conftest.py`
- Run: `uv run pytest tests/ -v`

## Code Style

- Python 3.11+ with `from __future__ import annotations`
- 4-space indentation
- Prefer `pydantic` models for data validation
- Use `httpx` for async HTTP, `requests` for sync HTTP
- Use `Path` from `pathlib` for file paths

## Linting

```bash
uv run ruff check src/ tests/
```
