"""Pure-Python token estimator (fallback when the Rust extension is absent).

Heuristics used here:
* CJK / full-width characters count as one token each;
* every other character counts as a quarter token.

Good enough for context budgeting; the Rust ``tokenizer`` extension replaces it
with real tiktoken BPE counts behind the same API.
"""

from typing import List

CHARS_PER_TOKEN = 4
SENTENCE_ENDINGS = "\u3002\uff01\uff1f\uff1b\n.!?;"
TRUNCATION_MARKER = "\n...(已截断)"


def _is_wide(ch: str) -> bool:
    code = ord(ch)
    return (
        0x3400 <= code <= 0x4DBF      # CJK extension A
        or 0x4E00 <= code <= 0x9FFF   # CJK unified ideographs
        or 0x3040 <= code <= 0x30FF   # hiragana / katakana
        or 0xAC00 <= code <= 0xD7AF   # hangul syllables
        or 0xF900 <= code <= 0xFAFF   # CJK compatibility ideographs
        or 0xFF00 <= code <= 0xFFEF   # full-width forms
    )


def count_tokens(text: str, model: str = "gpt-4o") -> int:
    if not text:
        return 0
    wide = 0
    for ch in text:
        if _is_wide(ch):
            wide += 1
    narrow = len(text) - wide
    return max(1, wide + (narrow + CHARS_PER_TOKEN - 1) // CHARS_PER_TOKEN)


def count_messages_tokens(texts: List[str], model: str = "gpt-4o") -> int:
    return sum(count_tokens(t, model) for t in texts)


def truncate_to_budget(text: str, budget_tokens: int, model: str = "gpt-4o") -> str:
    """Cut ``text`` to ``budget_tokens``, preferring a sentence/line boundary."""
    if not text:
        return ""
    if budget_tokens <= 0:
        return ""
    if count_tokens(text, model) <= budget_tokens:
        return text

    # The marker is appended afterwards, so it must fit inside the budget.
    effective = budget_tokens - count_tokens(TRUNCATION_MARKER, model)
    if effective <= 0:
        return TRUNCATION_MARKER.strip()

    low, high = 0, len(text)
    while low < high:
        mid = (low + high + 1) // 2
        if count_tokens(text[:mid], model) <= effective:
            low = mid
        else:
            high = mid - 1

    cut = low
    boundary = max(text.rfind(ch, 0, cut) for ch in SENTENCE_ENDINGS)
    if boundary >= int(cut * 0.6):
        cut = boundary + 1

    head = text[:cut].rstrip()
    return head + TRUNCATION_MARKER
