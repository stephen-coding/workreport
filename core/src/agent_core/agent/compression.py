"""Tool-output compression driven by the structural parser.

Large ``read_file`` / ``search_code`` payloads are shortened before they enter
the conversation: the head of the file is kept verbatim and a function outline
is appended, so the model still sees the shape of the code.
"""

import re
from typing import Optional

from agent_core.native import (
    count_tokens,
    detect_language,
    outline_of,
    truncate_to_budget,
)

TOOL_OUTPUT_BUDGET = 1800
HEAD_SHARE = 0.55

_LINE_PREFIX = re.compile(r"^\s*\d+\u2192")


def strip_line_numbers(text: str) -> str:
    """Remove ``12->`` style prefixes added by the Shell file reader."""
    if "\u2192" not in text:
        return text
    return "\n".join(_LINE_PREFIX.sub("", line) for line in text.splitlines())


def _language_hint(path: Optional[str], content: str) -> Optional[str]:
    if not path:
        return None
    return detect_language(path, content)


def compress_tool_output(
    tool_name: str,
    content: str,
    path: Optional[str] = None,
    budget: int = TOOL_OUTPUT_BUDGET,
) -> str:
    if not content or count_tokens(content) <= budget:
        return content

    if tool_name == "read_file":
        source = strip_line_numbers(content)
        language = _language_hint(path, source)
        if language:
            outline = outline_of(path or "", source)
            if outline:
                head = truncate_to_budget(source, int(budget * HEAD_SHARE))
                tail = truncate_to_budget(outline, max(1, budget - int(budget * HEAD_SHARE)))
                return "%s\n\n...（文件过长，以下仅列出结构概览）...\n%s" % (head, tail)

    return truncate_to_budget(content, budget)
