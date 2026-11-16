"""Terminal command policy.

The Core decides, the Shell only obeys: ``run_command`` resolves to one of three
decisions and the resulting value is what the Shell receives in
``tool/invoke.permission``.

* ``auto``    — allowlisted build/test/read commands, no prompt.
* ``confirm`` — anything else; the user approves once per execution.
* ``deny``    — destructive or irreversible commands; refused without a prompt.

Python 3.9 compatible.
"""

import re
from typing import Iterable, List, Optional

AUTO = "auto"
CONFIRM = "confirm"
DENY = "deny"

# Commands safe to run without asking. A trailing space is optional: ``mvn`` and
# ``mvn test`` both match, ``mvnw`` does not.
# 免确认的构建/测试/只读命令；前缀匹配按「整词」进行（mvn 不会误吞 mvnw）。
DEFAULT_ALLOWLIST: List[str] = [
    "mvn",
    "mvnw",
    "gradle",
    "gradlew",
    "npm",
    "npx",
    "pnpm",
    "yarn",
    "pytest",
    "python -m unittest",
    "python -m pytest",
    "python3 -m unittest",
    "python3 -m pytest",
    "cargo",
    "go test",
    "go build",
    "go vet",
    "dotnet test",
    "dotnet build",
    "git status",
    "git diff",
    "git log",
    "git branch",
    "git show",
    "ls",
    "dir",
    "cat",
    "type",
    "node -v",
    "node --version",
    "java -version",
]

# Matched as case-insensitive substrings of the whole command line, so chaining
# (``ls && rm -rf .``) cannot sneak a destructive step past the allowlist.
# 按整行子串匹配，链式命令中的危险片段同样会被拦下。
DENY_PATTERNS: List[str] = [
    "rm -rf",
    "rm -fr",
    "rm -r /",
    "rm --recursive",
    "del /f",
    "del /s",
    "del /q",
    "rd /s",
    "rmdir /s",
    "format ",
    "shutdown",
    "reboot",
    "mkfs",
    "diskpart",
    ":(){",
    "git reset --hard",
    "git push",
    "git clean -",
    "git branch -d",
    "curl | sh",
    "curl|sh",
    "wget | sh",
    "wget|sh",
    "invoke-expression",
    "iex(",
]

# Shell separators: every segment of a chain must pass the allowlist on its own.
# 分段判断：多段命令里任一段不在白名单内，整体就需要确认。
_SEGMENT_SPLIT = re.compile(r"&&|\|\||;|\||&|\n")
# Piping straight into an interpreter hides the payload from the patterns above,
# e.g. ``curl http://x | sh``; the segment after a pipe is checked by first word.
# 管道直接接解释器时载荷不在命令行里（curl ... | sh），按管后首词拦下；
# 用「首词 + 空白/结尾」避免把 shuf、shutdown 之类误判为 sh。
_PIPE_INTERPRETER = re.compile(r"^(sh|bash|zsh|powershell|pwsh|iex)(\s|$)")


def normalize(command: object) -> str:
    """Collapse whitespace so ``git   status`` matches the ``git status`` prefix."""
    return " ".join(str(command or "").split())


def _matches_prefix(segment: str, prefixes: Iterable[str]) -> bool:
    for raw in prefixes:
        prefix = raw.strip().lower()
        if not prefix:
            continue
        if segment == prefix or segment.startswith(prefix + " "):
            return True
    return False


def decide(command: object, allowlist: Optional[List[str]] = None) -> str:
    """Return ``auto`` / ``confirm`` / ``deny`` for one command line."""
    text = normalize(command)
    if not text:
        return DENY
    lowered = text.lower()
    for pattern in DENY_PATTERNS:
        if pattern in lowered:
            return DENY
    for piped in lowered.split("|")[1:]:
        if _PIPE_INTERPRETER.match(piped.strip()):
            return DENY
    prefixes = allowlist if allowlist else DEFAULT_ALLOWLIST
    segments = [part.strip() for part in _SEGMENT_SPLIT.split(lowered) if part.strip()]
    if segments and all(_matches_prefix(part, prefixes) for part in segments):
        return AUTO
    return CONFIRM
