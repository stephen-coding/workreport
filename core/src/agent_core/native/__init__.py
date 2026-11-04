"""Native acceleration facade.

The Rust extension ``agent_core_native`` is optional. Every function here has a
pure-Python fallback so the Core always starts; :func:`native_info` tells the
Shell whether the acceleration layer is loaded.
"""

import logging
from typing import List, Optional

from agent_core.native import codesoup as _py_codesoup
from agent_core.native import tokenizer as _py_tokenizer

LOGGER = logging.getLogger(__name__)

try:  # pragma: no cover - depends on the built wheel being importable
    import agent_core_native as _rust  # type: ignore
except Exception:  # ImportError, ABI mismatch, missing symbol ...
    _rust = None

BEHIND_RUST = _rust is not None
BACKEND = "rust" if BEHIND_RUST else "python"

FunctionNode = _py_codesoup.FunctionNode
ParsedFile = _py_codesoup.ParsedFile


def native_info() -> dict:
    return {
        "backend": BACKEND,
        "tokenizer": BACKEND,
        "codesoup": "rust" if BEHIND_RUST and hasattr(_rust, "parse_text") else "python",
    }


def count_tokens(text: str, model: str = "gpt-4o") -> int:
    if _rust is not None and hasattr(_rust, "count_tokens"):
        try:
            return int(_rust.count_tokens(text, model))
        except Exception:  # pragma: no cover - defensive
            LOGGER.warning("native tokenizer failed, falling back", exc_info=True)
    return _py_tokenizer.count_tokens(text, model)


def truncate_to_budget(text: str, budget_tokens: int, model: str = "gpt-4o") -> str:
    if _rust is not None and hasattr(_rust, "truncate_to_budget"):
        try:
            return str(_rust.truncate_to_budget(text, budget_tokens, model))
        except Exception:  # pragma: no cover - defensive
            LOGGER.warning("native truncate failed, falling back", exc_info=True)
    return _py_tokenizer.truncate_to_budget(text, budget_tokens, model)


def detect_language(path: str, content: Optional[str] = None) -> Optional[str]:
    if _rust is not None and hasattr(_rust, "detect_language"):
        try:
            language = _rust.detect_language(path, content)
            if language:
                return str(language)
        except Exception:  # pragma: no cover - defensive
            LOGGER.warning("native detect_language failed, falling back", exc_info=True)
    return _py_codesoup.detect_language(path, content)


def parse_text(content: str, language: str) -> ParsedFile:
    if _rust is not None and hasattr(_rust, "parse_text"):
        try:
            functions = [
                FunctionNode(
                    name=fn.name,
                    signature=fn.signature,
                    start_line=fn.start_line,
                    end_line=fn.end_line,
                    language=fn.language,
                )
                for fn in _rust.parse_text(content, language).functions
            ]
            return ParsedFile(language=language, functions=functions)
        except Exception:  # pragma: no cover - defensive
            LOGGER.warning("native parse_text failed, falling back", exc_info=True)
    return _py_codesoup.parse_text(content, language)


def outline_of(path: str, content: str) -> str:
    """Best-effort structural outline for a file, or '' when unknown."""
    language = detect_language(path, content)
    if not language:
        return ""
    parsed = parse_text(content, language)
    if not parsed.functions:
        return ""
    return "\n".join(fn.label() for fn in parsed.functions)
