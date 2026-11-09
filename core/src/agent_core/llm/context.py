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


def trim_history(messages: List[Message], budget: int = DEFAULT_BUDGET) -> List[Message]:
    """Keep the system prompt and drop the oldest rounds until in budget.

    Rounds go out whole: an assistant message that carries ``tool_calls`` is removed
    together with the tool results that follow it, otherwise the endpoint rejects the
    orphan ``tool`` message ("tool message must follow a tool_calls message").
    按“轮”从最旧丢：带 tool_calls 的 assistant 消息要和后面的 tool 结果一起丢，
    否则会留下孤立的 tool 消息，OpenAI 兼容端点会直接 400。
    """
    if messages_tokens(messages) <= budget:
        return list(messages)
    system = [m for m in messages if m.role.value == "system"]
    rest = [m for m in messages if m.role.value != "system"]
    while rest and messages_tokens(system + rest) > budget:
        dropped = rest.pop(0)
        if dropped.tool_calls:
            while rest and rest[0].role.value == "tool":
                rest.pop(0)
    # Never start with tool results whose assistant message was trimmed away.
    # 不要以孤立的 tool 结果开头。
    while rest and rest[0].role.value == "tool":
        rest.pop(0)
    return system + rest
