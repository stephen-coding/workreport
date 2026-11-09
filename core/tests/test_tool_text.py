"""Tests for tool calls written into the text channel (<tool_call> markup)."""

import asyncio
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

import httpx  # noqa: E402

from agent_core.llm import base as llm_base  # noqa: E402
from agent_core.llm.providers.openai_compat import OpenAICompatProvider  # noqa: E402
from agent_core.llm.tool_text import (  # noqa: E402
    TextToolCallFilter,
    recover_text_tool_calls,
)
from agent_core.protocol import ChatConfig, ToolDef  # noqa: E402

HERMES = (
    "<tool_call>\n"
    "<function=read_file>\n"
    "<parameter=path>\n"
    "src/main.py\n"
    "</parameter>\n"
    "<parameter=start_line>\n"
    "12\n"
    "</parameter>\n"
    "</function>\n"
    "</tool_call>"
)


def _drain(filt, pieces):
    out = []
    for piece in pieces:
        out.extend(filt.feed(piece))
    out.extend(filt.flush())
    return "".join(out)


class TestTextToolCallFilter(unittest.TestCase):
    def test_markup_split_across_chunks_is_hidden(self):
        payload = "先看看文件\n" + HERMES + "\n然后再说"
        filt = TextToolCallFilter()
        # One character per chunk: every marker is split at some boundary.
        visible = _drain(filt, list(payload))
        self.assertEqual(visible.strip(), "先看看文件\n\n然后再说".strip())
        self.assertNotIn("<tool_call>", visible)
        self.assertNotIn("read_file", visible)

    def test_plain_text_is_untouched(self):
        text = "这是一个普通回答，没有任何工具标记。"
        self.assertEqual(_drain(TextToolCallFilter(), list(text)), text)

    def test_bare_function_block_is_hidden(self):
        block = "<function=write_file>\n<parameter=path>\na.txt\n</parameter>\n</function>"
        self.assertEqual(_drain(TextToolCallFilter(), list("写入" + block)), "写入")

    def test_unterminated_block_is_dropped(self):
        # The model stopped mid-call: nothing half-written reaches the bubble.
        self.assertEqual(_drain(TextToolCallFilter(), list("开始<tool_call>\n<function=re")),
                         "开始")


class TestRecoverTextToolCalls(unittest.TestCase):
    def test_hermes_xml(self):
        cleaned, calls = recover_text_tool_calls("先看看\n" + HERMES)
        self.assertEqual(cleaned, "先看看")
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0].name, "read_file")
        self.assertEqual(calls[0].arguments, {"path": "src/main.py", "start_line": 12})
        self.assertTrue(calls[0].id)

    def test_json_block(self):
        content = ('<tool_call>\n{"name": "list_dir", "arguments": {"path": "."}}\n</tool_call>')
        cleaned, calls = recover_text_tool_calls(content)
        self.assertEqual(cleaned, "")
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0].name, "list_dir")
        self.assertEqual(calls[0].arguments, {"path": "."})

    def test_write_file_content_keeps_inner_newlines(self):
        content = (
            "<tool_call>\n<function=write_file>\n<parameter=path>\nnotes.txt\n</parameter>\n"
            "<parameter=content>\n第一行\n第二行\n</parameter>\n</function>\n</tool_call>"
        )
        _, calls = recover_text_tool_calls(content)
        self.assertEqual(calls[0].arguments["content"], "第一行\n第二行")

    def test_bare_function_block_is_recovered(self):
        content = ("<function=list_dir>\n<parameter=path>\ncore\n</parameter>\n</function>")
        cleaned, calls = recover_text_tool_calls(content)
        self.assertEqual(cleaned, "")
        self.assertEqual(calls[0].name, "list_dir")
        self.assertEqual(calls[0].arguments, {"path": "core"})

    def test_plain_text_is_returned_untouched(self):
        text = "普通回答，缩进  \n保持原样。"
        self.assertEqual(recover_text_tool_calls(text), (text, []))


class TestProviderRecoversTextCall(unittest.TestCase):
    def setUp(self):
        llm_base._tools_suppressed.clear()
        llm_base._notice_shown.clear()

    def test_stream_converts_markup_into_tool_call(self):
        content = "先看看\n" + HERMES
        chunks = [content[i:i + 5] for i in range(0, len(content), 5)]

        def handler(request):
            events = [{"choices": [{"delta": {"content": piece}, "finish_reason": None}]}
                      for piece in chunks]
            events.append({"choices": [{"delta": {}, "finish_reason": "stop"}]})
            body = "".join("data: %s\n\n" % json.dumps(e) for e in events)
            return httpx.Response(200, content=(body + "data: [DONE]\n\n").encode("utf-8"))

        provider = OpenAICompatProvider(transport=httpx.MockTransport(handler))
        config = ChatConfig(provider="openai_compat", model="qwen-3.8-27b",
                            base_url="https://host/v1/chat/completions")
        tokens = []
        seen = []

        async def collect(token):
            tokens.append(token)

        async def remember(call):
            seen.append(call)

        async def scenario():
            return await provider.chat_stream(
                [], [ToolDef(name="read_file", description="r")], config, collect, remember)

        result = asyncio.run(scenario())

        self.assertEqual(len(result.tool_calls), 1)
        self.assertEqual(result.tool_calls[0].name, "read_file")
        self.assertEqual(result.tool_calls[0].arguments["path"], "src/main.py")
        self.assertEqual(result.content, "先看看")
        self.assertEqual([c.name for c in seen], ["read_file"])
        streamed = "".join(tokens)
        self.assertNotIn("<tool_call>", streamed)
        self.assertNotIn("</function>", streamed)


if __name__ == "__main__":
    unittest.main()
