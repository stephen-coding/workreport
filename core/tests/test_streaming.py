"""Tests for the SSE tool-call accumulation state machines."""

import json
import unittest

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.llm.streaming import (  # noqa: E402
    OllamaStreamAccumulator,
    OpenAIStreamAccumulator,
    parse_sse_line,
)


class TestParseSseLine(unittest.TestCase):
    def test_data_line(self):
        self.assertEqual(parse_sse_line("data: {\"a\":1}"), "{\"a\":1}")

    def test_non_data_line(self):
        self.assertIsNone(parse_sse_line("event: message"))
        self.assertIsNone(parse_sse_line(""))


class TestOpenAIAccumulator(unittest.TestCase):
    def test_text_tokens(self):
        acc = OpenAIStreamAccumulator()
        tokens = []
        tokens += acc.feed({"choices": [{"delta": {"content": "你"}, "index": 0}]})
        tokens += acc.feed({"choices": [{"delta": {"content": "好"}, "index": 0}]})
        self.assertEqual(tokens, ["你", "好"])
        self.assertEqual(acc.content, "你好")

    def test_tool_call_fragments_across_chunks(self):
        acc = OpenAIStreamAccumulator()
        acc.feed({"choices": [{"delta": {"tool_calls": [
            {"index": 0, "id": "call_1", "function": {"name": "read_file", "arguments": "{\"pa"}},
        ]}}]})
        acc.feed({"choices": [{"delta": {"tool_calls": [
            {"index": 0, "function": {"arguments": "th\": \"a.java\"}"}},
        ]}, "finish_reason": "tool_calls"}]})
        calls = acc.finalize()
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0].id, "call_1")
        self.assertEqual(calls[0].name, "read_file")
        self.assertEqual(calls[0].arguments, {"path": "a.java"})
        self.assertEqual(acc.finish_reason, "tool_calls")

    def test_parallel_tool_calls_two_slots(self):
        acc = OpenAIStreamAccumulator()
        acc.feed({"choices": [{"delta": {"tool_calls": [
            {"index": 0, "id": "c0", "function": {"name": "a", "arguments": "{}"}},
            {"index": 1, "id": "c1", "function": {"name": "b", "arguments": "{}"}},
        ]}}]})
        calls = acc.finalize()
        self.assertEqual([c.name for c in calls], ["a", "b"])

    def test_invalid_arguments_kept_raw(self):
        acc = OpenAIStreamAccumulator()
        acc.feed({"choices": [{"delta": {"tool_calls": [
            {"index": 0, "id": "c0", "function": {"name": "x", "arguments": "{oops"}},
        ]}}]})
        calls = acc.finalize()
        self.assertEqual(calls[0].arguments, {"_raw": "{oops"})


class TestOllamaAccumulator(unittest.TestCase):
    def test_text_and_usage(self):
        acc = OllamaStreamAccumulator()
        tokens = acc.feed({"message": {"content": "hello"}, "done": False})
        tokens += acc.feed({"message": {"content": " world"}, "done": True,
                            "prompt_eval_count": 10, "eval_count": 2})
        self.assertEqual("".join(tokens), "hello world")
        self.assertTrue(acc.done)
        self.assertEqual(acc.usage, {"in": 10, "out": 2})

    def test_whole_tool_calls_deduplicated(self):
        acc = OllamaStreamAccumulator()
        call = {"function": {"name": "read_file", "arguments": {"path": "a.java"}}}
        acc.feed({"message": {"content": "", "tool_calls": [call]}, "done": False})
        acc.feed({"message": {"content": "", "tool_calls": [call]}, "done": False})
        calls = acc.finalize()
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0].arguments, {"path": "a.java"})

    def test_arguments_as_json_string(self):
        acc = OllamaStreamAccumulator()
        acc.feed({"message": {"tool_calls": [
            {"function": {"name": "x", "arguments": json.dumps({"k": 1})}},
        ]}})
        self.assertEqual(acc.finalize()[0].arguments, {"k": 1})


if __name__ == "__main__":
    unittest.main()
