"""Shared HTTP transport settings and user-facing failure text.

httpcore builds ``ReadTimeout`` / ``ReadError`` / ``ConnectError`` without a
message, so a stalled or dropped stream used to reach the Shell as a bare
``[错误:LLM_ERROR] `` line with nothing after it.  Every transport failure must
name the phase, the endpoint and what to try next.
"""

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
