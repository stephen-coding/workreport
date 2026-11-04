"""SKILL.md loader.

Skills are Markdown files with a small frontmatter block::

    ---
    name: daily-report
    description: 生成日报
    scenarios: ["生成日报", "写日报"]
    tools: [git_log]
    ---
    # 指令
    ...

Search order (later wins): builtin -> ``~/.agent/skills`` -> ``<project>/.agent/skills``.
"""

import logging
import os
import re
from typing import Dict, List, Optional, Tuple

from pydantic import BaseModel, Field

LOGGER = logging.getLogger(__name__)

BUILTIN_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "builtin")
SKILL_FILE = "SKILL.md"
_FRONTMATTER_RE = re.compile(r"^---\s*\n(.*?)\n---\s*\n?(.*)$", re.S)


class Skill(BaseModel):
    name: str
    description: str = ""
    scenarios: List[str] = Field(default_factory=list)
    tools: List[str] = Field(default_factory=list)
    body: str = ""
    scope: str = "builtin"  # builtin | global | project
    path: str = ""

    def matches(self, text: str) -> bool:
        lowered = (text or "").lower()
        return any(scenario and scenario.lower() in lowered for scenario in self.scenarios)

    def render(self) -> str:
        header = "遵循以下 Skill 指令完成任务：\n# Skill: %s\n%s" % (self.name, self.description)
        if self.tools:
            header += "\n建议工具: %s" % ", ".join(self.tools)
        return header + "\n\n" + self.body.strip()


def _strip_quotes(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def parse_frontmatter(text: str) -> Tuple[Dict[str, object], str]:
    match = _FRONTMATTER_RE.match(text.lstrip("\ufeff"))
    if not match:
        return {}, text
    meta: Dict[str, object] = {}
    current_key: Optional[str] = None
    for raw_line in match.group(1).splitlines():
        line = raw_line.rstrip()
        if not line.strip():
            continue
        stripped = line.lstrip()
        if stripped.startswith("- ") and current_key:
            bucket = meta.get(current_key)
            if not isinstance(bucket, list):
                bucket = []
                meta[current_key] = bucket
            bucket.append(_strip_quotes(stripped[2:].strip()))
            continue
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        key = key.strip()
        value = value.strip()
        current_key = key
        if not value:
            meta[key] = []
        elif value.startswith("[") and value.endswith("]"):
            meta[key] = [_strip_quotes(part.strip())
                         for part in value[1:-1].split(",") if part.strip()]
        else:
            meta[key] = _strip_quotes(value)
    return meta, match.group(2)


def parse_skill(text: str, path: str, scope: str) -> Optional[Skill]:
    meta, body = parse_frontmatter(text)
    name = str(meta.get("name") or "").strip()
    if not name:
        return None
    scenarios = meta.get("scenarios") or []
    tools = meta.get("tools") or []
    return Skill(
        name=name,
        description=str(meta.get("description") or "").strip(),
        scenarios=[str(s) for s in scenarios] if isinstance(scenarios, list) else [str(scenarios)],
        tools=[str(t) for t in tools] if isinstance(tools, list) else [str(tools)],
        body=body.strip(),
        scope=scope,
        path=path,
    )


class SkillLoader:
    def __init__(self, project_root: Optional[str] = None,
                 global_dir: Optional[str] = None) -> None:
        self.project_root = project_root
        self.global_dir = global_dir or os.path.join(os.path.expanduser("~"), ".agent", "skills")
        self._cache: Optional[Dict[str, Skill]] = None

    # ------------------------------------------------------------------ scan

    def _candidate_dirs(self) -> List[Tuple[str, str]]:
        dirs: List[Tuple[str, str]] = [(BUILTIN_DIR, "builtin")]
        if self.global_dir:
            dirs.append((self.global_dir, "global"))
        if self.project_root:
            dirs.append((os.path.join(self.project_root, ".agent", "skills"), "project"))
        return dirs

    def _read_dir(self, directory: str, scope: str) -> List[Skill]:
        if not os.path.isdir(directory):
            return []
        found: List[Skill] = []
        for entry in sorted(os.listdir(directory)):
            entry_path = os.path.join(directory, entry)
            if os.path.isdir(entry_path):
                skill_path = os.path.join(entry_path, SKILL_FILE)
                if not os.path.isfile(skill_path):
                    continue
            elif entry.endswith(".md"):
                skill_path = entry_path
            else:
                continue
            try:
                with open(skill_path, "r", encoding="utf-8") as handle:
                    skill = parse_skill(handle.read(), skill_path, scope)
            except OSError:
                LOGGER.warning("cannot read skill %s", skill_path)
                continue
            if skill is not None:
                found.append(skill)
        return found

    def load_all(self, refresh: bool = False) -> Dict[str, Skill]:
        if self._cache is not None and not refresh:
            return self._cache
        skills: Dict[str, Skill] = {}
        for directory, scope in self._candidate_dirs():
            for skill in self._read_dir(directory, scope):
                if skill.name in skills:
                    LOGGER.info("skill %s overridden by %s scope", skill.name, scope)
                skills[skill.name] = skill
        self._cache = skills
        return skills

    # ----------------------------------------------------------------- query

    def list_skills(self) -> List[Skill]:
        return sorted(self.load_all().values(), key=lambda s: s.name)

    def get(self, name: str) -> Optional[Skill]:
        return self.load_all().get(name)

    def match(self, user_input: str) -> Optional[Skill]:
        for skill in self.list_skills():
            if skill.matches(user_input):
                return skill
        return None

    def summary(self) -> str:
        skills = self.list_skills()
        if not skills:
            return ""
        lines = ["可用 Skill："]
        for skill in skills:
            lines.append("- %s: %s" % (skill.name, skill.description))
        return "\n".join(lines)
