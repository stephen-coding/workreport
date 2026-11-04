"""Embedding client for the semantic code index.

Uses the same endpoint as the active chat profile:
* ``ollama``        -> ``/api/embed`` (falls back to the legacy ``/api/embeddings``)
* ``openai_compat`` -> ``/v1/embeddings``
"""

import logging
from typing import List

import httpx

from agent_core.protocol import ChatConfig, ErrorCode, RpcError

LOGGER = logging.getLogger(__name__)

_TIMEOUT = httpx.Timeout(connect=5.0, read=60.0, write=20.0, pool=5.0)
BATCH_SIZE = 16


def resolve_embed_url(base_url: str) -> str:
    base = (base_url or "").strip().rstrip("/")
    if not base:
        return ""
    if base.endswith("/api/embed"):
        return base
    if base.endswith("/api/chat"):
        base = base[: -len("/api/chat")]
    return base + "/api/embed"


def resolve_openai_embed_url(base_url: str) -> str:
    base = (base_url or "").strip().rstrip("/")
    if not base:
        return ""
    if base.endswith("/embeddings"):
        return base
    if base.endswith("/chat/completions"):
        base = base[: -len("/chat/completions")]
    return base + "/embeddings"


class Embedder:
    """Turns text into vectors; the model name is part of the vector identity."""

    def __init__(self, config: ChatConfig, model: str) -> None:
        self.config = config
        self.model = (model or "").strip()
        self.dim = 0

    def describe(self) -> str:
        return "%s/%s" % (self.config.provider or "ollama", self.model)

    async def embed(self, texts: List[str]) -> List[List[float]]:
        if not texts:
            return []
        if not self.model:
            raise RpcError(ErrorCode.PROTOCOL_ERROR, "未配置 Embedding 模型")
        vectors: List[List[float]] = []
        for start in range(0, len(texts), BATCH_SIZE):
            batch = [t[:4000] for t in texts[start:start + BATCH_SIZE]]
            if (self.config.provider or "").lower() == "openai_compat":
                vectors.extend(await self._openai(batch))
            else:
                vectors.extend(await self._ollama(batch))
        return vectors

    async def _ollama(self, texts: List[str]) -> List[List[float]]:
        url = resolve_embed_url(self.config.base_url)
        if not url:
            raise RpcError(ErrorCode.LLM_ERROR, "base_url 未配置")
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            response = await client.post(url, json={"model": self.model, "input": texts})
            if response.status_code == 404:
                return await self._ollama_legacy(texts)
            if response.status_code >= 400:
                raise RpcError(ErrorCode.LLM_ERROR,
                               "Ollama embedding 失败(%d): %s"
                               % (response.status_code, response.text[:300]))
            data = response.json()
        vectors = data.get("embeddings") or []
        self._remember_dim(vectors)
        return vectors

    async def _ollama_legacy(self, texts: List[str]) -> List[List[float]]:
        url = resolve_embed_url(self.config.base_url).replace("/api/embed", "/api/embeddings")
        vectors: List[List[float]] = []
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            for text in texts:
                response = await client.post(url, json={"model": self.model, "prompt": text})
                if response.status_code >= 400:
                    raise RpcError(ErrorCode.LLM_ERROR,
                                   "Ollama embedding 失败(%d): %s"
                                   % (response.status_code, response.text[:300]))
                vectors.append(list(response.json().get("embedding") or []))
        self._remember_dim(vectors)
        return vectors

    async def _openai(self, texts: List[str]) -> List[List[float]]:
        url = resolve_openai_embed_url(self.config.base_url)
        if not url:
            raise RpcError(ErrorCode.LLM_ERROR, "base_url 未配置")
        headers = {"Content-Type": "application/json"}
        if self.config.api_key:
            headers["Authorization"] = "Bearer " + self.config.api_key
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            response = await client.post(url, json={"model": self.model, "input": texts},
                                         headers=headers)
            if response.status_code >= 400:
                raise RpcError(ErrorCode.LLM_ERROR,
                               "embedding 失败(%d): %s"
                               % (response.status_code, response.text[:300]))
            data = response.json()
        rows = data.get("data") or []
        rows.sort(key=lambda item: int(item.get("index") or 0))
        vectors = [list(item.get("embedding") or []) for item in rows]
        self._remember_dim(vectors)
        return vectors

    def _remember_dim(self, vectors: List[List[float]]) -> None:
        for vector in vectors:
            if vector:
                self.dim = len(vector)
                return
