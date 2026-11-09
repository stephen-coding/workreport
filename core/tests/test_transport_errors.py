"""Transport failures must explain themselves.

httpcore raises ReadTimeout / ReadError / ConnectError with an empty message, so
a stalled or dropped stream used to reach the Shell as ``[错误:LLM_ERROR] `` with
nothing after it. These tests pin the replacement text and the provider wiring.
"""

import asyncio
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

import httpx  # noqa: E402

from agent_core.llm.providers.ollama import OllamaProvider  # noqa: E402
from agent_core.llm.providers.openai_compat import OpenAICompatProvider  # noqa: E402
from agent_core.llm.transport import READ_TIMEOUT, describe_transport_error  # noqa: E402
from agent_core.protocol import ChatConfig, ErrorCode, RpcError  # noqa: E402

URL = "http://host:8000/v1/chat/completions"
MODEL = "glm-5.1"


def _describe(exc):
    return describe_transport_error(exc, URL, MODEL)


class TestDescribeTransportError(unittest.TestCase):

    def test_read_timeout_names_the_phase_and_the_endpoint(self):
        error = _describe(httpx.ReadTimeout(""))
        self.assertEqual(error.code, ErrorCode.LLM_ERROR)
        self.assertIn("超时", str(error))
        self.assertIn("%.0f" % READ_TIMEOUT, str(error))
        self.assertIn(URL, str(error))
        self.assertIn(MODEL, str(error))
        self.assertIn("继续", str(error))

    def test_connect_timeout_points_at_the_connect_phase(self):
        self.assertIn("建立连接", str(_describe(httpx.ConnectTimeout(""))))

    def test_dropped_stream_is_not_blank(self):
        error = _describe(httpx.ReadError(""))
        self.assertIn("连接中断", str(error))
        self.assertTrue(str(error).strip())

    def test_connect_error_talks_about_the_endpoint(self):
        self.assertIn("无法建立连接", str(_describe(httpx.ConnectError(""))))

    def test_unknown_transport_error_still_has_text(self):
        error = _describe(httpx.TransportError(""))
        self.assertIn("LLM 网络错误", str(error))


class _FailingTransport(httpx.MockTransport):
    """Raise one transport error instead of answering."""

    def __init__(self, exc):
        self._exc = exc
        super().__init__(self._raise)

    def _raise(self, request):
        raise self._exc


class TestProvidersSurfaceTransportErrors(unittest.TestCase):

    def _config(self):
        return ChatConfig(provider="openai_compat", model=MODEL, base_url=URL)

    def _run(self, provider):
        async def noop(_token):
            return None

        async def scenario():
            return await provider.chat_stream([], None, self._config(), noop, noop)

        with self.assertRaises(RpcError) as raised:
            asyncio.run(scenario())
        return raised.exception

    def test_openai_provider_never_reports_an_empty_message(self):
        error = self._run(OpenAICompatProvider(transport=_FailingTransport(httpx.ReadError(""))))
        self.assertEqual(error.code, ErrorCode.LLM_ERROR)
        self.assertIn("连接中断", str(error))

    def test_ollama_provider_never_reports_an_empty_message(self):
        error = self._run(OllamaProvider(transport=_FailingTransport(httpx.ReadTimeout(""))))
        self.assertEqual(error.code, ErrorCode.LLM_ERROR)
        self.assertIn("超时", str(error))


class TestServerErrorText(unittest.TestCase):

    def test_empty_exception_falls_back_to_the_class_name(self):
        from agent_core.rpc.server import _error_text

        self.assertEqual(_error_text(TimeoutError()), "TimeoutError")
        self.assertEqual(_error_text(RuntimeError("boom")), "boom")


if __name__ == "__main__":
    unittest.main()
