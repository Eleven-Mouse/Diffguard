"""Unstructured -> ChromaDB context ingest service."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

import chromadb
from chromadb.api.models.Collection import Collection
from unstructured.partition.auto import partition

from app.config import settings


@dataclass(frozen=True)
class IngestResult:
    path: str
    chunks: int
    collection: str


class ContextIngestor:
    """Extracts document text with unstructured and writes chunks to ChromaDB."""

    def __init__(self) -> None:
        headers = {}
        if settings.CHROMA_API_TOKEN:
            headers["Authorization"] = f"Bearer {settings.CHROMA_API_TOKEN}"
        self._client = chromadb.HttpClient(host=settings.CHROMA_URL, headers=headers)
        self._collection_name = settings.CHROMA_COLLECTION
        self._collection: Collection | None = None

    def ingest_file(self, path: Path, namespace: str = "default") -> IngestResult:
        if not path.exists() or not path.is_file():
            raise FileNotFoundError(f"context file not found: {path}")

        text = self._extract_text(path)
        chunks = self._chunk_text(text)
        if not chunks:
            return IngestResult(path=str(path), chunks=0, collection=self._collection_name)

        ids: list[str] = []
        documents: list[str] = []
        metadatas: list[dict] = []
        for idx, chunk in enumerate(chunks):
            cid = self._chunk_id(namespace=namespace, path=path, idx=idx, text=chunk)
            ids.append(cid)
            documents.append(chunk)
            metadatas.append(
                {
                    "namespace": namespace,
                    "source_path": str(path),
                    "chunk_index": idx,
                }
            )

        self._get_or_create_collection().upsert(ids=ids, documents=documents, metadatas=metadatas)
        return IngestResult(path=str(path), chunks=len(chunks), collection=self._collection_name)

    def _get_or_create_collection(self) -> Collection:
        if self._collection is None:
            self._collection = self._client.get_or_create_collection(name=self._collection_name)
        return self._collection

    @staticmethod
    def _extract_text(path: Path) -> str:
        elements = partition(filename=str(path))
        parts = [str(e).strip() for e in elements if str(e).strip()]
        return "\n\n".join(parts)

    @staticmethod
    def _chunk_id(namespace: str, path: Path, idx: int, text: str) -> str:
        digest = hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]
        return f"{namespace}:{path.name}:{idx}:{digest}"

    @staticmethod
    def _chunk_text(text: str) -> Sequence[str]:
        max_chars = max(200, settings.UNSTRUCTURED_MAX_CHUNK_CHARS)
        overlap = max(0, min(settings.UNSTRUCTURED_CHUNK_OVERLAP, max_chars // 2))
        text = text.strip()
        if not text:
            return []
        if len(text) <= max_chars:
            return [text]

        chunks: list[str] = []
        start = 0
        while start < len(text):
            end = min(len(text), start + max_chars)
            chunk = text[start:end].strip()
            if chunk:
                chunks.append(chunk)
            if end >= len(text):
                break
            start = max(0, end - overlap)
        return chunks

