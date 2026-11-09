"""Tests for provider auto-detection and the tools-unsupported fallback."""

import asyncio
import json
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

import httpx  # noqa: E402

from agent_core.llm import build_provider, detect_provider  # noqa: E402
from agent_core.llm import base as llm_base  # noqa: E402
from agent_core.llm.base import is_tool_unsupported  # noqa: E402
from agent_core.llm.providers.ollama import OllamaProvider, resolve_chat_url as ollama_url  # noqa: E402
from agent_core.llm.providers.openai_compat import (  # noqa: E402
    OpenAICompatProvider,
    resolve_chat_url as openai_url,
)
from agent_core.protocol import ChatConfig, ToolDef  # noqa: E402

VLLM_REJECT = json.dumps({
    "error": {
        "message": "\"auto\" tool choice requires --enable-auto-tool-choice "
                   "and --tool-call-parser to be set",
        "type": "BadRequestError",
        "code": 400,
    }
})

TOOLS = [ToolDef(name="read_file", description="read a file",
                 parameters={"type": "object", "properties": {}})]


def _sse(*chunks):
    body = "".join("data: %s\n\n" % json.dumps(chunk) for chunk in chunks)
    return (body + "data: [DONE]\n\n").encode("utf-8")


def _ndjson(*chunks):
    return ("\n".join(json.dumps(chunk) for chunk in chunks) + "\n").encode("utf-8")


class TestProviderDetection(unittest.TestCase):
    def test_openai_endpoint_wins_over_ollama_type(self):
        # The reported 404: /v1/chat/completions + "ollama" became .../chat/completions/api/chat
        config = ChatConfig(provider="ollama", base_url="http://host:8000/v1/chat/completions")
        self.assertEqual(detect_provider(config), "openai_compat")

    def test_ollama_endpoint_wins_over_openai_type(self):
        config = ChatConfig(provider="openai_compat", base_url="http://localhost:11434/api/chat")
        self.assertEqual(detect_provider(config), "ollama")

    def test_v1_suffix_is_openai(self):
        config = ChatConfig(provider="ollama", base_url="https://api.deepseek.com/v1")
        self.assertEqual(detect_provider(config), "openai_compat")

    def test_root_url_keeps_configured_type(self):
        self.assertEqual(detect_provider(ChatConfig(provider="ollama",
                                                    base_url="http://localhost:11434")), "ollama")
        self.assertEqual(detect_provider(ChatConfig(provider="openai_compat",
                                                    base_url="https://api.deepseek.com")),
                         "openai_compat")

    def test_build_provider_follows_detection(self):
        ollama_like = build_provider(ChatConfig(provider="openai_compat",
                                                base_url="http://localhost:11434/api/chat"))
        openai_like = build_provider(ChatConfig(provider="ollama",
                                                base_url="https://host/v1/chat/completions"))
        self.assertIsInstance(ollama_like, OllamaProvider)
        self.assertIsInstance(openai_like, OpenAICompatProvider)


class TestChatUrls(unittest.TestCase):
    def test_ollama_resolver(self):
        self.assertEqual(ollama_url("http://localhost:11434/api/chat"),
                         "http://localhost:11434/api/chat")
        self.assertEqual(ollama_url("http://localhost:11434/api/generate"),
                         "http://localhost:11434/api/chat")
        self.assertEqual(ollama_url("http://localhost:11434"),
                         "http://localhost:11434/api/chat")

    def test_openai_resolver(self):
        self.assertEqual(openai_url("https://host/v1/chat/completions"),
                         "https://host/v1/chat/completions")
        self.assertEqual(openai_url("https://host/v1"), "https://host/v1/chat/completions")
        self.assertEqual(openai_url("https://host"), "https://host/v1/chat/completions")


class TestToolUnsupportedDetection(unittest.TestCase):
    def test_vllm_message_is_detected(self):
        self.assertTrue(is_tool_unsupported(400, VLLM_REJECT))

    def test_ollama_message_is_detected(self):
        self.assertTrue(is_tool_unsupported(
            400, '{"error":"registry.ollama.ai/library/llama:latest does not support tools"}'))

    def test_other_errors_are_not_tool_related(self):
        self.assertFalse(is_tool_unsupported(400, '{"error":"model not found"}'))
        self.assertFalse(is_tool_unsupported(500, VLLM_REJECT))
        self.assertFalse(is_tool_unsupported(401, "invalid api key"))


class TestOpenAIToolsFallback(unittest.TestCase):
    def setUp(self):
        # Probation state is module level, so every test starts from a clean slate.
        # 假释状态在模块级，每个用例都从头开始。
        llm_base._tools_suppressed.clear()
        llm_base._notice_shown.clear()

    def test_retries_without_tools_and_reports_once(self):
        sent = []

        def handler(request):
            payload = json.loads(request.content)
            sent.append(payload)
            if "tools" in payload:
                return httpx.Response(400, content=VLLM_REJECT.encode("utf-8"))
            return httpx.Response(200, content=_sse(
                {"choices": [{"delta": {"content": "你好"}, "finish_reason": "stop"}]}))

        provider = OpenAICompatProvider(transport=httpx.MockTransport(handler))
        config = ChatConfig(provider="openai_compat", model="glm-5.1",
                            base_url="https://host/v1/chat/completions")
        tokens = []

        async def scenario():
            return await provider.chat_stream(
                [], TOOLS, config, lambda t: _collect(tokens, t), _noop_tool_call)

        result = asyncio.run(scenario())

        self.assertEqual(result.content, "你好")
        self.assertEqual(len(sent), 2)
        self.assertIn("tools", sent[0])
        self.assertNotIn("tools", sent[1])
        self.assertNotIn("tool_choice", sent[1])
        notice = provider.consume_notice()
        self.assertIn("函数调用", notice)
        self.assertIsNone(provider.consume_notice())

    def test_known_endpoint_skips_tools_on_next_task(self):
        llm_base.suppress_tools("https://host/v1/chat/completions", "glm-5.1")
        sent = []

        def handler(request):
            sent.append(json.loads(request.content))
            return httpx.Response(200, content=_sse(
                {"choices": [{"delta": {"content": "ok"}, "finish_reason": "stop"}]}))

        provider = OpenAICompatProvider(transport=httpx.MockTransport(handler))
        config = ChatConfig(provider="openai_compat", model="glm-5.1",
                            base_url="https://host/v1/chat/completions")

        async def scenario():
            return await provider.chat_stream([], TOOLS, config, _noop_token, _noop_tool_call)

        result = asyncio.run(scenario())

        self.assertEqual(result.content, "ok")
        self.assertEqual(len(sent), 1)
        self.assertNotIn("tools", sent[0])
        self.assertIsNotNone(provider.consume_notice())

    def test_probation_expires_so_a_fixed_endpoint_is_reprobed(self):
        # Ops restarted vLLM with --enable-auto-tool-choice; the next task must try
        # tools again instead of staying in plain-chat mode until the IDE restarts.
        # 运维重启 vLLM 后，下一次任务应重新带工具，而不是继续纯对话。
        key = ("https://host/v1/chat/completions", "glm-5.1")
        llm_base.suppress_tools(*key)
        llm_base._tools_suppressed[key] = time.monotonic() - 1.0
        sent = []

        def handler(request):
            sent.append(json.loads(request.content))
            return httpx.Response(200, content=_sse(
                {"choices": [{"delta": {"content": "ok"}, "finish_reason": "stop"}]}))

        provider = OpenAICompatProvider(transport=httpx.MockTransport(handler))
        config = ChatConfig(provider="openai_compat", model="glm-5.1",
                            base_url="https://host/v1/chat/completions")

        async def scenario():
            return await provider.chat_stream([], TOOLS, config, _noop_token, _noop_tool_call)

        asyncio.run(scenario())

        self.assertEqual(len(sent), 1)
        self.assertIn("tools", sent[0])
        self.assertFalse(llm_base.tools_suppressed(*key))

    def test_notice_is_shown_once_per_endpoint_across_tasks(self):
        # A fresh provider is built for every task, so the notice flag must live
        # outside the provider instance.
        # 每个任务都会新建 provider，提示去重必须放在模块级。
        key = ("https://host/v1/chat/completions", "glm-5.1")
        llm_base.suppress_tools(*key)
        config = ChatConfig(provider="openai_compat", model="glm-5.1",
                            base_url="https://host/v1/chat/completions")

        def handler(request):
            return httpx.Response(200, content=_sse(
                {"choices": [{"delta": {"content": "ok"}, "finish_reason": "stop"}]}))

        async def scenario():
            first = OpenAICompatProvider(transport=httpx.MockTransport(handler))
            await first.chat_stream([], TOOLS, config, _noop_token, _noop_tool_call)
            second = OpenAICompatProvider(transport=httpx.MockTransport(handler))
            await second.chat_stream([], TOOLS, config, _noop_token, _noop_tool_call)
            return first.consume_notice(), second.consume_notice()

        first_notice, second_notice = asyncio.run(scenario())

        self.assertIn("函数调用", first_notice)
        self.assertIsNone(second_notice)

    def test_accept_tools_clears_probation_and_notice_flag(self):
        key = ("https://host/v1/chat/completions", "glm-5.1")
        llm_base.suppress_tools(*key)
        self.assertTrue(llm_base.take_tools_notice(*key))

        llm_base.accept_tools(*key)

        self.assertFalse(llm_base.tools_suppressed(*key))
        self.assertTrue(llm_base.take_tools_notice(*key))


class TestOllamaToolsFallback(unittest.TestCase):
    def setUp(self):
        llm_base._tools_suppressed.clear()
        llm_base._notice_shown.clear()

    def test_retries_without_tools(self):
        sent = []

        def handler(request):
            payload = json.loads(request.content)
            sent.append(payload)
            if "tools" in payload:
                return httpx.Response(
                    400, content=b'{"error":"model \\"llama3\\" does not support tools"}')
            return httpx.Response(200, content=_ndjson(
                {"message": {"content": "ok"}, "done": True}))

        provider = OllamaProvider(transport=httpx.MockTransport(handler))
        config = ChatConfig(provider="ollama", model="llama3",
                            base_url="http://localhost:11434/api/chat")
        tokens = []

        async def scenario():
            return await provider.chat_stream(
                [], TOOLS, config, lambda t: _collect(tokens, t), _noop_tool_call)

        result = asyncio.run(scenario())

        self.assertEqual(result.content, "ok")
        self.assertEqual(len(sent), 2)
        self.assertNotIn("tools", sent[1])
        self.assertIn("工具调用", provider.consume_notice())


async def _collect(sink, token):
    sink.append(token)


async def _noop_token(token):
    return None


async def _noop_tool_call(call):
    return None


if __name__ == "__main__":
    unittest.main()
