# Contributing to DiffGuard

Thank you for your interest in contributing to DiffGuard! This guide covers the development setup and contribution process.

## Development Setup

### Prerequisites

- Java 21 (temurin distribution recommended)
- Python 3.11+
- [uv](https://docs.astral.sh/uv/) Python package manager
- Git

### Java Gateway

```bash
cd services/gateway
mvn -B verify        # build + test
```

### Python Agent

```bash
cd services/agent
uv sync --dev        # install dependencies
uv run pytest tests/ -v   # run tests
uv run ruff check src/ tests/  # lint
```

### Docker (both services)

```bash
docker-compose build
docker-compose up
```

## Branch Naming

- `feat/<short-description>` — new features
- `fix/<short-description>` — bug fixes
- `refactor/<short-description>` — code restructuring
- `docs/<short-description>` — documentation changes

## Pull Request Process

1. Fork the repository and create a feature branch
2. Make your changes with clear, descriptive commit messages
3. Ensure all tests pass (`mvn -B verify` for Java, `uv run pytest tests/ -v` for Python)
4. Ensure linting passes (`uv run ruff check src/ tests/` for Python)
5. Open a PR against the `main` branch with a clear description

## Code Style

- **Java**: See [JAVA.md](JAVA.md)
- **Python**: See [PYTHON.md](PYTHON.md)

## Architecture

- **Java Gateway**: Hexagonal architecture (adapter/domain/infrastructure)
- **Python Agent**: 4-stage pipeline (summary → review → aggregation → FP filter)
- See [AGENT.md](AGENT.md) for detailed architecture documentation
