"""Token budget estimation and history trimming.

Counting is delegated to :mod:`agent_core.native`, which uses the Rust
tiktoken extension when it is importable and a cheap character heuristic
otherwise.
"""

from typing import List

from agent_core.native import count_tokens
from agent_core.protocol import Message

DEFAULT_BUDGET = 24000  # conservative budget for 8k-32k window models


def estimate_tokens(text: str) -> int:
    return count_tokens(text or "")


def messages_tokens(messages: List[Message]) -> int:
    total = 0
    for m in messages:
        total += estimate_tokens(m.content or "")
        for tc in m.tool_calls or []:
            total += estimate_tokens(tc.name) + estimate_tokens(str(tc.arguments))
    return total


def _first_non_user(messages: List[Message]) -> int:
    for index, message in enumerate(messages):
        if message.role.value != "user":
            return index
    return -1


def _drop_at(messages: List[Message], index: int) -> None:
    """Drop one message together with the tool results it owns."""
    dropped = messages.pop(index)
    if dropped.tool_calls:
        while index < len(messages) and messages[index].role.value == "tool":
            messages.pop(index)


def trim_history(messages: List[Message], budget: int = DEFAULT_BUDGET) -> List[Message]:
    """Keep the system prompt and drop the oldest rounds until in budget.

    Rounds go out whole: an assistant message that carries ``tool_calls`` is removed
    together with the tool results that follow it, otherwise the endpoint rejects the
    orphan ``tool`` message ("tool message must follow a tool_calls message").
    按“轮”从最旧丢：带 tool_calls 的 assistant 消息要和后面的 tool 结果一起丢，
    否则会留下孤立的 tool 消息，OpenAI 兼容端点会直接 400。

    Stale history goes first and the user's own prompt always survives, because vLLM
    answers 400 ``No user query found in messages.`` to a request without any
    ``user`` role. That 400 used to hit long tasks once the opening prompt was
    trimmed away.
    先丢历史、再丢本轮的工具轮，但用户提问本身永不丢弃——整段请求没有 user 角色时
    vLLM 会回 400 ``No user query found in messages.``，长任务里开头的提问被丢就会踩到。
    """
    if messages_tokens(messages) <= budget:
        return list(messages)
    system = [m for m in messages if m.role.value == "system"]
    rest = [m for m in messages if m.role.value != "system"]

    # Pass 1: drop whole old turns (prompt + answers), keeping the newest turn.
    while messages_tokens(system + rest) > budget:
        if not rest or rest[0].role.value != "user":
            break
        turns = [i for i, m in enumerate(rest) if m.role.value == "user"]
        if len(turns) < 2:
            break
        del rest[0:turns[1]]

    # Pass 2: inside the surviving turn drop the oldest tool round, never a prompt.
    while messages_tokens(system + rest) > budget:
        index = _first_non_user(rest)
        if index < 0:
            break
        _drop_at(rest, index)

    # Never start with tool results whose assistant message was trimmed away.
    # 不要以孤立的 tool 结果开头。
    while rest and rest[0].role.value == "tool":
        rest.pop(0)
    return system + rest
