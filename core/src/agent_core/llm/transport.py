"""Shared HTTP transport settings, bounded retries and user-facing failure text.

httpcore builds ``ReadTimeout`` / ``ReadError`` / ``ConnectError`` without a
message, so a stalled or dropped stream used to reach the Shell as a bare
``[错误:LLM_ERROR] `` line with nothing after it.  Every transport failure must
name the phase, the endpoint and what to try next.
"""

import asyncio
from typing import Awaitable, Callable, TypeVar

import httpx

from agent_core.protocol import ErrorCode, RpcError

# Timeouts shared by every provider (nobody wants two different read budgets).
# 各提供方共用同一组超时：连接 10s、读 180s、写 30s、连接池 10s。
CONNECT_TIMEOUT = 10.0
READ_TIMEOUT = 180.0
WRITE_TIMEOUT = 30.0
POOL_TIMEOUT = 10.0

HTTP_TIMEOUT = httpx.Timeout(connect=CONNECT_TIMEOUT, read=READ_TIMEOUT,
                             write=WRITE_TIMEOUT, pool=POOL_TIMEOUT)

_RETRY_HINT = "确认服务端还在运行后，重发一次「继续」即可接着跑。"


def _timeout_phase(exc: httpx.TimeoutException):
    if isinstance(exc, httpx.ConnectTimeout):
        return "建立连接", CONNECT_TIMEOUT
    if isinstance(exc, httpx.WriteTimeout):
        return "发送请求", WRITE_TIMEOUT
    if isinstance(exc, httpx.PoolTimeout):
        return "获取连接", POOL_TIMEOUT
    return "等待模型输出", READ_TIMEOUT


def describe_transport_error(exc: Exception, url: str, model: str) -> RpcError:
    """Turn a broken/slow LLM connection into an explanatory ``LLM_ERROR``.

    ``str(exc)`` is empty for most httpcore errors, which is exactly why the
    Shell showed nothing useful.  The text below never depends on it.
    """
    target = url or "未配置端点"
    if model:
        target += "（模型 %s）" % model

    if isinstance(exc, httpx.TimeoutException):
        phase, seconds = _timeout_phase(exc)
        text = ("LLM 请求超时：%s 在%s阶段超过 %.0f 秒没有响应。"
                "多轮对话后提示词变长、或服务端正在排队处理其它会话时，首字延迟会明显变大；"
                "确认服务端未卡死后重发一次「继续」即可（也可调小 max_tokens 或减少历史轮次）。"
                % (target, phase, seconds))
    elif isinstance(exc, httpx.ConnectError):
        text = ("LLM 连接失败：%s 无法建立连接。请确认端点地址与端口正确、服务已启动、"
                "代理或防火墙放行；恢复后重发一次「继续」即可。" % target)
    elif isinstance(exc, httpx.NetworkError):
        # ReadError / WriteError / CloseError: the socket died mid-stream, which
        # httpcore reports with an empty message.
        text = ("LLM 连接中断：%s 在传输响应时被断开。服务端重启、请求被超时终止或网络闪断都会这样；%s"
                % (target, _RETRY_HINT))
    elif isinstance(exc, httpx.ProtocolError):
        text = ("LLM 响应协议错误：%s 返回的数据不完整或无法解析。%s" % (target, _RETRY_HINT))
    else:
        detail = str(exc).strip()
        text = "LLM 网络错误（%s）：%s%s" % (
            type(exc).__name__, target, "；" + detail if detail else "；" + _RETRY_HINT)

    return RpcError(ErrorCode.LLM_ERROR, text)


# --- bounded retries ---------------------------------------------------------
# A 429/5xx or a connect failure is safe to replay only while nothing has been
# rendered. After the first token the Shell already shows text and a replayed
# round would duplicate tool side effects.
# 只在尚未输出内容时重放；已渲染 token 或工具已产生副作用时不重试。
RETRYABLE_STATUS = frozenset({429, 500, 502, 503, 504})
MAX_RETRIES = 2
BACKOFF_SEC = (0.5, 1.5)

T = TypeVar("T")


class RetryableStatus(Exception):
    """An HTTP status worth retrying (429 / 5xx)."""

    def __init__(self, status: int, body: str = "") -> None:
        super().__init__("%d: %s" % (status, (body or "")[:200]))
        self.status = status
        self.body = body


def is_retryable(exc: Exception) -> bool:
    if isinstance(exc, RetryableStatus):
        return True
    # Connect failures and pool/read timeouts happen before any answer byte.
    return isinstance(exc, (httpx.ConnectError, httpx.ConnectTimeout,
                            httpx.PoolTimeout, httpx.ReadTimeout))


def describe_failure(exc: Exception, url: str, model: str, retries: int) -> RpcError:
    """Build the final error, noting how many automatic retries were spent."""
    if isinstance(exc, RetryableStatus):
        suffix = "，已自动重试 %d 次" % retries if retries else ""
        text = ("LLM 端点暂时不可用：%s（模型 %s）返回 HTTP %d%s。"
                "服务端重启、限流或过载都会这样；稍后重发一次「继续」即可。"
                % (url or "未配置端点", model or "-", exc.status, suffix))
        return RpcError(ErrorCode.LLM_ERROR, text)
    error = describe_transport_error(exc, url, model)
    if retries:
        return RpcError(error.code, str(error) + "（已自动重试 %d 次）" % retries)
    return error


async def run_with_retries(attempt: Callable[[], Awaitable[T]],
                           started: Callable[[], bool],
                           describe: Callable[[Exception, int], RpcError]) -> T:
    """Run one streaming attempt, retrying only failures that produced no output."""
    retries = 0
    while True:
        try:
            return await attempt()
        except Exception as exc:
            if not is_retryable(exc):
                # Transport errors that are not safe to replay still owe the user an
                # explanation (httpcore's message is empty).
                # 不可重试的传输错误也要给出可读文案：httpcore 的异常信息是空的。
                if isinstance(exc, httpx.TransportError):
                    raise describe(exc, 0) from exc
                raise
            if started() or retries >= MAX_RETRIES:
                raise describe(exc, retries) from exc
            await asyncio.sleep(BACKOFF_SEC[retries])
            retries += 1
