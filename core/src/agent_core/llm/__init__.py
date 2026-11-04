"""Provider factory."""

from agent_core.llm.providers.ollama import OllamaProvider
from agent_core.llm.providers.openai_compat import OpenAICompatProvider
from agent_core.protocol import ChatConfig


def build_provider(config: ChatConfig):
    if (config.provider or "").lower() == "openai_compat":
        return OpenAICompatProvider()
    return OllamaProvider()
