"""Pure-Python structural parser (fallback for the Rust ``codesoup`` extension).

Only function/class outlines are produced. Tree-sitter queries are not
supported in the fallback: :meth:`ParsedFile.query` returns an empty list and
callers fall back to text search.
"""

import ast
import os
import re
from dataclasses import dataclass, field
from typing import Dict, List, Optional

LANGUAGE_BY_EXTENSION: Dict[str, str] = {
    ".py": "python",
    ".pyi": "python",
    ".rs": "rust",
    ".js": "javascript",
    ".jsx": "javascript",
    ".mjs": "javascript",
    ".cjs": "javascript",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".java": "java",
    ".go": "go",
    ".c": "c",
    ".h": "c",
    ".cpp": "cpp",
    ".cc": "cpp",
    ".cxx": "cpp",
    ".hpp": "cpp",
    ".cs": "csharp",
    ".kt": "kotlin",
    ".kts": "kotlin",
}

SHEBANG_LANGUAGES = (
    ("python", "python"),
    ("node", "javascript"),
)

# Brace-delimited languages: (function header regex, brace language flag).
_REGEX_PATTERNS: Dict[str, re.Pattern] = {
    "rust": re.compile(
        r"^[ \t]*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:const\s+)?(?:unsafe\s+)?fn\s+(\w+)"),
    "javascript": re.compile(
        r"^[ \t]*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s*\*?\s*(\w+)\s*\("),
    "typescript": re.compile(
        r"^[ \t]*(?:export\s+)?(?:default\s+)?(?:abstract\s+)?(?:async\s+)?function\s*\*?\s*(\w+)\s*\("),
    "java": re.compile(
        r"^[ \t]*(?:public|protected|private|static|final|abstract|synchronized|native|default|\s)+"
        r"[\w<>\[\],.?\s]+\s+(\w+)\s*\([^;]*$"),
    "go": re.compile(r"^func\s+(?:\([^)]*\)\s*)?(\w+)\s*\("),
    "c": re.compile(r"^[A-Za-z_][\w\s*]*\s+(\w+)\s*\([^;]*$"),
    "cpp": re.compile(r"^[A-Za-z_][\w\s*:<>,&]*\s+(\w+)\s*\([^;]*$"),
    "csharp": re.compile(
        r"^[ \t]*(?:public|protected|private|internal|static|virtual|override|async|\s)+"
        r"[\w<>\[\],.?\s]+\s+(\w+)\s*\("),
    "kotlin": re.compile(r"^[ \t]*(?:public|private|internal|protected|suspend|override|\s)*fun\s+(\w+)\s*\("),
}


@dataclass
class FunctionNode:
    name: str
    signature: str
    start_line: int
    end_line: int
    language: str

    def label(self) -> str:
        return "%s  [L%d-L%d]" % (self.name, self.start_line, self.end_line)


@dataclass
class ParsedFile:
    language: str
    functions: List[FunctionNode] = field(default_factory=list)

    def query(self, s_expr: str) -> List[FunctionNode]:
        # Tree-sitter queries are only available in the Rust extension.
        return []

    def outline(self) -> str:
        if not self.functions:
            return ""
        return "\n".join("%s(%s)" % (fn.name, fn.signature) for fn in self.functions)


def detect_language(path: str, content: Optional[str] = None) -> Optional[str]:
    if path:
        _, ext = os.path.splitext(path.lower())
        language = LANGUAGE_BY_EXTENSION.get(ext)
        if language:
            return language
    if content and content.startswith("#!"):
        first_line = content.splitlines()[0]
        for marker, language in SHEBANG_LANGUAGES:
            if marker in first_line:
                return language
    return None


def _brace_end(lines: List[str], start_index: int) -> int:
    """Return the 1-based line where the brace block opened at ``start_index`` closes."""
    depth = 0
    opened = False
    for index in range(start_index, len(lines)):
        for ch in lines[index]:
            if ch == "{":
                depth += 1
                opened = True
            elif ch == "}":
                depth -= 1
                if opened and depth <= 0:
                    return index + 1
    return len(lines)


def _parse_python(content: str) -> List[FunctionNode]:
    try:
        tree = ast.parse(content)
    except SyntaxError:
        return []

    nodes: List[FunctionNode] = []

    def visit(body, prefix: str) -> None:
        for node in body:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                nodes.append(FunctionNode(
                    name=prefix + node.name,
                    signature=_python_signature(node),
                    start_line=node.lineno,
                    end_line=getattr(node, "end_lineno", None) or node.lineno,
                    language="python",
                ))
            elif isinstance(node, ast.ClassDef):
                visit(node.body, prefix + node.name + ".")
                nodes.append(FunctionNode(
                    name=prefix + node.name,
                    signature="class",
                    start_line=node.lineno,
                    end_line=getattr(node, "end_lineno", None) or node.lineno,
                    language="python",
                ))

    visit(tree.body, "")
    nodes.sort(key=lambda n: n.start_line)
    return nodes


def _python_signature(node) -> str:
    try:
        args = ast.unparse(node.args)
    except Exception:  # pragma: no cover - very old interpreters
        return ""
    prefix = "async def " if isinstance(node, ast.AsyncFunctionDef) else "def "
    return prefix + args


def _parse_regex(content: str, language: str) -> List[FunctionNode]:
    pattern = _REGEX_PATTERNS.get(language)
    if pattern is None:
        return []
    lines = content.splitlines()
    nodes: List[FunctionNode] = []
    for index, line in enumerate(lines):
        if line.startswith((" ", "\t")) and language in ("go",):
            continue
        match = pattern.match(line)
        if not match:
            continue
        name = match.group(1)
        if name in ("if", "for", "while", "switch", "catch", "return", "new", "sizeof"):
            continue
        end = _brace_end(lines, index)
        nodes.append(FunctionNode(
            name=name,
            signature=line.strip()[:160],
            start_line=index + 1,
            end_line=end,
            language=language,
        ))
    return nodes


def parse_text(content: str, language: str) -> ParsedFile:
    if not content:
        return ParsedFile(language=language)
    if language == "python":
        functions = _parse_python(content)
    else:
        functions = _parse_regex(content, language)
    return ParsedFile(language=language, functions=functions)
