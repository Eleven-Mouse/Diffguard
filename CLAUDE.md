# CLAUDE.md — AI Agent Collaboration Guide

This file provides guidance for AI coding assistants working on the DiffGuard project.

## Project Overview

DiffGuard is an AI-powered code review tool with two services:
- **Java Gateway** (`services/gateway/`) — Webhook server, tool server, AST analysis, code graph
- **Python Agent** (`services/agent/`) — 4-stage review pipeline using LLMs

## Key Documentation

- [AGENT.md](AGENT.md) — Complete architecture, call chains, and modification guidelines
- [JAVA.md](JAVA.md) — Java coding standards
- [PYTHON.md](PYTHON.md) — Python coding standards

## Quick Reference

| Task | Command |
|------|---------|
| Java tests | `cd services/gateway && mvn -B verify` |
| Python tests | `cd services/agent && uv run pytest tests/ -v` |
| Python lint | `cd services/agent && uv run ruff check src/ tests/` |
| Docker build | `docker-compose build` |
