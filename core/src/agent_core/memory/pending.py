"""Candidate memories extracted at the end of a chat, waiting for review.

The Core never writes these into long-term memory on its own.  Candidates live
under ``memories/_pending/<session>.md`` — still Markdown, still local — and are
invisible to ``memory_overview`` until the user promotes one through
``memory/promote``.  Fewer but clean is the whole point: rule one and rule two
below are deliberately narrow, everything else is dropped.

会话结束后只生成"候选记忆"，落在 memories/_pending 下，确认后才转正；
它们不进入 memory_overview，因此不会污染后续对话。
"""

import os
import re
import time
from typing import Any, Dict, List, Optional

from agent_core.memory.files import slugify
from agent_core.protocol import Message, Role

PENDING_DIR_NAME = "_pending"
# One turn rarely yields more than a couple of instructions; capping keeps a
# rambling message from flooding the review list.
MAX_CANDIDATES = 8
MIN_CONTENT_CHARS = 6
MAX_CONTENT_CHARS = 300

# Rule 1: the user asked to keep something ("remember / from now on / never").
# 规则一：用户明确要求长期记住的指令。
_INSTRUCTION_MARKERS = (
    "记住", "以后都", "以后", "不要", "必须", "别再", "永远",
    "一律", "统一用", "记得", "禁止",
)

# Rule 2: the user corrected a fact in the same round.
# 规则二：同一轮里用户纠正了事实。
_CORRECTION_PATTERNS = (
    re.compile(r"不对[，,。！!：:\s]"),
    re.compile(r"^\s*不是"),
    re.compile(r"不是[^。！？\n]{0,20}而是"),
    re.compile(r"说错了"),
    re.compile(r"应该是"),
    re.compile(r"纠正"),
)

_SENTENCE_SPLIT = re.compile(r"[。．！!\n；;]+")
_FENCE_RE = re.compile(r"```.*?```", re.S)
_WORD_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_.\-]{1,30}")
_CJK_RE = re.compile(r"[\u4e00-\u9fff]{2,6}")
_QUESTION_TAILS = ("？", "?", "吗", "么")

SOURCE_INSTRUCTION = "user_instruction"
SOURCE_CORRECTION = "user_correction"


def _flatten_blocks(text: str) -> str:
    """Drop fenced code: instructions live in prose, code is not a memory."""
    return _FENCE_RE.sub(" ", text or "")


def _sentences(text: str) -> List[str]:
    out: List[str] = []
    for raw in _SENTENCE_SPLIT.split(_flatten_blocks(text)):
        flat = " ".join(raw.split()).strip("，,：:、 ")
        if flat:
            out.append(flat)
    return out


def _is_question(sentence: str) -> bool:
    return sentence.rstrip().endswith(_QUESTION_TAILS)


def _clip(text: str) -> str:
    return text if len(text) <= MAX_CONTENT_CHARS else text[:MAX_CONTENT_CHARS].rstrip() + "…"


def derive_title(content: str) -> str:
    """A short, declarative title; the leading marker is dropped."""
    flat = " ".join(content.split())
    for marker in _INSTRUCTION_MARKERS:
        if flat.startswith(marker):
            flat = flat[len(marker):].lstrip("，,：: ")
            break
    flat = flat.strip()
    if not flat:
        flat = "用户要求"
    return flat[:24]


def derive_keywords(content: str, limit: int = 5) -> List[str]:
    keywords: List[str] = []
    for match in _WORD_RE.findall(content):
        word = match.strip("._-")
        if len(word) >= 2 and word not in keywords:
            keywords.append(word)
        if len(keywords) >= limit:
            return keywords
    for match in _CJK_RE.findall(content):
        if match not in keywords:
            keywords.append(match)
        if len(keywords) >= limit:
            break
    return keywords


def extract_candidates(messages: List[Message]) -> List[Dict[str, Any]]:
    """Apply rule one and rule two to the user turns; no model call involved."""
    candidates: List[Dict[str, Any]] = []
    seen = set()
    for message in messages:
        if message.role != Role.USER or not (message.content or "").strip():
            continue
        for sentence in _sentences(message.content or ""):
            if len(sentence) < MIN_CONTENT_CHARS or _is_question(sentence):
                continue
            category = ""
            if any(marker in sentence for marker in _INSTRUCTION_MARKERS):
                category = "user_preference"
                source = SOURCE_INSTRUCTION
            elif any(pattern.search(sentence) for pattern in _CORRECTION_PATTERNS):
                category = "correction"
                source = SOURCE_CORRECTION
            if not category:
                continue
            content = _clip(sentence)
            key = normalize_content(content)
            if key in seen:
                continue
            seen.add(key)
            candidates.append({
                "title": derive_title(content),
                "content": content,
                "category": category,
                "source": source,
                "keywords": derive_keywords(content),
            })
            if len(candidates) >= MAX_CANDIDATES:
                return candidates
    return candidates


def normalize_content(text: str) -> str:
    """Comparison key: case, whitespace and punctuation never matter."""
    return re.sub(r"[\s\W_]+", "", (text or "").lower())


class PendingStore:
    """One Markdown file per conversation under ``memories/_pending``."""

    def __init__(self, directory: str) -> None:
        self.directory = directory

    # ------------------------------------------------------------------ paths

    def _path(self, session_id: str) -> str:
        return os.path.join(self.directory, "%s.md" % slugify(session_id, 60))

    # ---------------------------------------------------------------- parsing

    def _read(self, path: str) -> Dict[str, Any]:
        """Return ``{"session": str, "created": int, "items": [...]}``."""
        empty = {"session": "", "created": 0, "items": []}
        if not os.path.exists(path):
            return empty
        try:
            with open(path, "r", encoding="utf-8") as handle:
                text = handle.read()
        except OSError:
            return empty
        header: Dict[str, str] = {}
        body = text
        if text.startswith("---"):
            end = text.find("\n---", 3)
            if end != -1:
                for line in text[3:end].splitlines():
                    if ":" in line:
                        key, value = line.split(":", 1)
                        header[key.strip()] = value.strip()
                body = text[end + 4:]
        items: List[Dict[str, Any]] = []
        for chunk in re.split(r"(?m)^## ", body):
            chunk = chunk.strip()
            if not chunk:
                continue
            lines = chunk.splitlines()
            pending_id = lines[0].strip()
            meta: Dict[str, str] = {}
            index = 1
            while index < len(lines) and lines[index].strip():
                if ":" in lines[index]:
                    key, value = lines[index].split(":", 1)
                    meta[key.strip()] = value.strip()
                index += 1
            content = "\n".join(lines[index:]).strip()
            if not pending_id or not content:
                continue
            items.append({
                "id": pending_id,
                "sessionId": header.get("session", ""),
                "title": meta.get("title") or derive_title(content),
                "content": content,
                "category": meta.get("category") or "user_preference",
                "source": meta.get("source") or SOURCE_INSTRUCTION,
                "keywords": [k.strip() for k in (meta.get("keywords") or "").split(",")
                             if k.strip()],
                "created": int(meta.get("created") or header.get("created") or 0),
            })
        return {
            "session": header.get("session", ""),
            "created": int(header.get("created") or 0),
            "items": items,
        }

    def _write(self, path: str, session_id: str, created: int,
               items: List[Dict[str, Any]]) -> None:
        if not items:
            try:
                os.remove(path)
            except FileNotFoundError:
                pass
            return
        os.makedirs(self.directory, exist_ok=True)
        lines = ["---", "session: %s" % session_id,
                 "source: chat", "created: %d" % (created or int(time.time())),
                 "---", ""]
        for item in items:
            lines.append("## %s" % item["id"])
            lines.append("title: %s" % item["title"])
            lines.append("category: %s" % item["category"])
            lines.append("source: %s" % item["source"])
            lines.append("keywords: %s" % ", ".join(item["keywords"]))
            lines.append("created: %d" % int(item.get("created") or time.time()))
            lines.append("")
            lines.append(item["content"].strip())
            lines.append("")
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines).rstrip() + "\n")

    # ------------------------------------------------------------------- read

    def _files(self) -> List[str]:
        if not os.path.isdir(self.directory):
            return []
        return sorted(os.path.join(self.directory, name)
                      for name in os.listdir(self.directory) if name.endswith(".md"))

    def list_all(self) -> List[Dict[str, Any]]:
        items: List[Dict[str, Any]] = []
        for path in self._files():
            items.extend(self._read(path)["items"])
        items.sort(key=lambda item: item.get("created") or 0, reverse=True)
        return items

    def get(self, pending_id: str) -> Optional[Dict[str, Any]]:
        for item in self.list_all():
            if item["id"] == pending_id:
                return item
        return None

    # ------------------------------------------------------------------ write

    def add(self, session_id: str, candidates: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Append unseen candidates; returns only the ones actually written."""
        if not candidates:
            return []
        known = {normalize_content(item["content"]) for item in self.list_all()}
        path = self._path(session_id)
        state = self._read(path)
        items = state["items"]
        used = set()
        for item in items:
            tail = item["id"].rsplit("-", 1)[-1]
            if tail.isdigit():
                used.add(int(tail))
        prefix = slugify(session_id, 40)
        added: List[Dict[str, Any]] = []
        for candidate in candidates:
            key = normalize_content(candidate["content"])
            if not key or key in known:
                continue
            known.add(key)
            number = max(used) + 1 if used else 1
            used.add(number)
            entry = {
                "id": "%s-%d" % (prefix, number),
                "sessionId": session_id,
                "title": candidate["title"],
                "content": candidate["content"],
                "category": candidate["category"],
                "source": candidate["source"],
                "keywords": candidate.get("keywords") or [],
                "created": int(time.time()),
            }
            items.append(entry)
            added.append(entry)
        if added:
            self._write(path, session_id, state["created"] or int(time.time()), items)
        return added

    def update(self, pending_id: str, content: Optional[str] = None,
               title: Optional[str] = None, category: Optional[str] = None,
               keywords: Optional[List[str]] = None) -> Optional[Dict[str, Any]]:
        for path in self._files():
            state = self._read(path)
            for item in state["items"]:
                if item["id"] != pending_id:
                    continue
                if content is not None and content.strip():
                    item["content"] = _clip(content.strip())
                    item["keywords"] = keywords or derive_keywords(item["content"])
                    item["title"] = title or derive_title(item["content"])
                elif title:
                    item["title"] = title
                if category:
                    item["category"] = category
                if keywords and content is None:
                    item["keywords"] = keywords
                self._write(path, item["sessionId"], state["created"], state["items"])
                return item
        return None

    def remove(self, pending_id: str) -> bool:
        for path in self._files():
            state = self._read(path)
            remaining = [item for item in state["items"] if item["id"] != pending_id]
            if len(remaining) == len(state["items"]):
                continue
            self._write(path, state["session"], state["created"], remaining)
            return True
        return False
