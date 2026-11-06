"""Provider factory."""

import logging

from agent_core.llm.providers.ollama import OllamaProvider
from agent_core.llm.providers.openai_compat import OpenAICompatProvider
from agent_core.protocol import ChatConfig

LOGGER = logging.getLogger(__name__)

# Endings that unambiguously belong to the Ollama native protocol.
# 明确属于 Ollama 原生协议的端点结尾。
_OLLAMA_SUFFIXES = ("/api/chat", "/api/generate", "/api/embed", "/api/embeddings", "/api/tags")


def detect_provider(config: ChatConfig) -> str:
    """Resolve the protocol from the endpoint shape, falling back to the configured type.

    The settings page lets users paste any full endpoint, so a ``/v1/chat/completions``
    URL configured as ``ollama`` used to become ``.../v1/chat/completions/api/chat``
    and fail with 404. The URL shape is authoritative here.
    设置页允许填任意完整端点，所以当端点形状与“类型”列冲突时以端点为准：
    填了 /v1/chat/completions 就按 OpenAI 兼容处理，填了 /api/chat 就按 Ollama 处理。
    """
    configured = (config.provider or "").strip().lower()
    base = (config.base_url or "").strip().lower().rstrip("/")
    if not base:
        return configured or "ollama"
    if any(base.endswith(suffix) for suffix in _OLLAMA_SUFFIXES):
        detected = "ollama"
    elif "/chat/completions" in base or base.endswith("/v1"):
        detected = "openai_compat"
    else:
        detected = "openai_compat" if configured == "openai_compat" else "ollama"
    if configured and configured != detected:
        LOGGER.info("provider auto-detect: configured=%s endpoint=%s -> %s",
                    configured, config.base_url, detected)
    return detected


def build_provider(config: ChatConfig):
    if detect_provider(config) == "openai_compat":
        return OpenAICompatProvider()
    return OllamaProvider()
