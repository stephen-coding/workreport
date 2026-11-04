"""Tests for the ghost-text completion helpers (URLs + sanitising)."""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.llm.completion import (  # noqa: E402
    resolve_completions_url,
    resolve_generate_url,
    sanitize,
)


class TestCompletionUrls(unittest.TestCase):
    def test_ollama_generate_url_from_chat_endpoint(self):
        self.assertEqual(resolve_generate_url("http://localhost:11434/api/chat"),
                         "http://localhost:11434/api/generate")
        self.assertEqual(resolve_generate_url("http://localhost:11434"),
                         "http://localhost:11434/api/generate")
        self.assertEqual(resolve_generate_url("http://localhost:11434/api/generate"),
                         "http://localhost:11434/api/generate")

    def test_openai_completions_url(self):
        self.assertEqual(resolve_completions_url("https://api.deepseek.com/v1"),
                         "https://api.deepseek.com/v1/completions")
        self.assertEqual(resolve_completions_url("https://host/v1/chat/completions"),
                         "https://host/v1/completions")
        self.assertEqual(resolve_completions_url("https://host/v1/completions"),
                         "https://host/v1/completions")


class TestSanitize(unittest.TestCase):
    def test_strips_markdown_fence(self):
        text = "```java\nint a = 1;\n```"
        self.assertEqual(sanitize(text, "class A {", "}"), "int a = 1;")

    def test_cuts_repeated_suffix_line(self):
        suffix = "    return result;\n}"
        text = "    int x = compute();\n    return result;"
        self.assertEqual(sanitize(text, "int f() {", suffix),
                         "    int x = compute();")

    def test_drops_prefix_echo(self):
        prefix = "public void run() {"
        self.assertEqual(sanitize("public void run() {\n    start();", prefix, "}"),
                         "    start();")

    def test_keeps_plain_completion(self):
        self.assertEqual(sanitize("\n    return value;", "int f() {", "}"),
                         "\n    return value;")

    def test_empty_text(self):
        self.assertEqual(sanitize("", "a", "b"), "")


if __name__ == "__main__":
    unittest.main()
