"""Fill-in-the-middle completion used by the editor ghost text.

The Shell sends the text before / after the caret and renders whatever text we
return as an inline suggestion. Completion is best-effort: providers that do not
support FIM simply produce an empty suggestion instead of an error.
"""

import logging

import httpx

from agent_core.protocol import ChatConfig

LOGGER = logging.getLogger(__name__)

_TIMEOUT = httpx.Timeout(connect=2.0, read=8.0, write=2.0, pool=2.0)
MAX_PREFIX_CHARS = 4000
MAX_SUFFIX_CHARS = 1200


def resolve_generate_url(base_url: str) -> str:
    base = (base_url or "").strip().rstrip("/")
    if not base:
        return ""
    if base.endswith("/api/generate"):
        return base
    if base.endswith("/api/chat"):
        base = base[: -len("/api/chat")]
    return base + "/api/generate"


def resolve_completions_url(base_url: str) -> str:
    base = (base_url or "").strip().rstrip("/")
    if not base:
        return ""
    if base.endswith("/chat/completions"):
        base = base[: -len("/chat/completions")]
    if base.endswith("/completions"):
        return base
    return base + "/completions"


async def complete(config: ChatConfig, prefix: str, suffix: str = "",
                   language: str = "", max_tokens: int = 128) -> str:
    """Return the text that should be inserted at the caret ('' when unsure)."""
    prefix = (prefix or "")[-MAX_PREFIX_CHARS:]
    suffix = (suffix or "")[:MAX_SUFFIX_CHARS]
    if not prefix.strip():
        return ""
    try:
        if (config.provider or "").lower() == "openai_compat":
            text = await _complete_openai(config, prefix, suffix, max_tokens)
        else:
            text = await _complete_ollama(config, prefix, suffix, max_tokens)
    except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
        # Ghost text must never surface errors in the editor.
        LOGGER.debug("inline completion failed: %s", exc)
        return ""
    return sanitize(text, prefix, suffix)


async def _complete_ollama(config: ChatConfig, prefix: str, suffix: str,
                           max_tokens: int) -> str:
    url = resolve_generate_url(config.base_url)
    if not url:
        return ""
    body = {
        "model": config.model,
        "prompt": prefix,
        "suffix": suffix,
        "stream": False,
        "options": {
            "temperature": 0.1,
            "num_predict": max(16, int(max_tokens)),
            "stop": ["\n\n"],
        },
    }
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        response = await client.post(url, json=body)
        if response.status_code >= 400:
            LOGGER.debug("ollama generate %s: %s", response.status_code,
                         response.text[:200])
            return ""
        data = response.json()
    return str(data.get("response") or "")


async def _complete_openai(config: ChatConfig, prefix: str, suffix: str,
                           max_tokens: int) -> str:
    url = resolve_completions_url(config.base_url)
    if not url:
        return ""
    headers = {"Content-Type": "application/json"}
    if config.api_key:
        headers["Authorization"] = "Bearer " + config.api_key
    body = {
        "model": config.model,
        "prompt": prefix,
        "suffix": suffix,
        "max_tokens": max(16, int(max_tokens)),
        "temperature": 0.1,
        "stop": ["\n\n"],
    }
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        response = await client.post(url, json=body, headers=headers)
        if response.status_code >= 400:
            # Many cloud models have no /completions endpoint at all.
            LOGGER.debug("completions %s: %s", response.status_code,
                         response.text[:200])
            return ""
        data = response.json()
    choices = data.get("choices") or []
    if not choices:
        return ""
    return str(choices[0].get("text") or "")


def sanitize(text: str, prefix: str, suffix: str) -> str:
    """Trim fences, stop at code that already follows the caret, drop echoes."""
    if not text:
        return ""
    text = text.replace("\r\n", "\n")
    text = _strip_fences(text)
    # Models often repeat the upcoming lines; cut before the first suffix line.
    for line in (suffix or "").splitlines():
        stripped = line.strip()
        if len(stripped) < 4:
            continue
        index = text.find(stripped)
        if index >= 0:
            text = text[:index]
            break
    text = text.rstrip()
    if not text:
        return ""
    # Never echo the tail of the prefix back into the editor.
    tail = prefix.strip().splitlines()[-1].strip() if prefix.strip() else ""
    if len(tail) >= 8 and text.strip().startswith(tail):
        text = text.strip()[len(tail):].lstrip("\n").rstrip()
    if prefix.endswith("\n") and text.startswith("\n"):
        text = text[1:]
    return text if text.strip() else ""


def _strip_fences(text: str) -> str:
    lines = text.split("\n")
    kept = [line for line in lines if not line.strip().startswith("```")]
    return "\n".join(kept)
