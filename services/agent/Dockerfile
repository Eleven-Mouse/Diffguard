FROM python:3.12-slim

WORKDIR /app

# Non-root user
RUN groupadd -r diffguard && useradd -r -g diffguard diffguard

# Install uv
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

# Copy project files
COPY pyproject.toml ./

# Install dependencies
RUN uv pip install --system -r pyproject.toml

# Copy application code (diffguard/ is the latest version)
COPY diffguard/ ./app/

EXPOSE 8000

USER diffguard

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/api/v1/health')" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
