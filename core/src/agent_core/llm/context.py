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


def _message_tokens(message: Message) -> int:
    total = estimate_tokens(message.content or "")
    for call in message.tool_calls or []:
        total += estimate_tokens(call.name) + estimate_tokens(str(call.arguments))
    return total


def messages_tokens(messages: List[Message]) -> int:
    return sum(_message_tokens(message) for message in messages)


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

    Token counts are computed once per message and then only subtracted. Recounting
    the whole history after every dropped round made a long conversation quadratic:
    150 tool rounds took 40 s inside the event loop, which froze every session (and
    made changing the model look useless because ``chat/start`` never ran).
    每条消息只分词一次，之后只做减法和前缀跳过：历史上每丢一轮就重算全量的话，
    150 轮要 40 秒且同步阻塞事件循环，表现为“聊久了换什么模型都卡死”。
    """
    system = [m for m in messages if m.role.value == "system"]
    rest = [m for m in messages if m.role.value != "system"]
    counts = [_message_tokens(message) for message in rest]
    total = sum(_message_tokens(message) for message in system) + sum(counts)
    if total <= budget:
        return list(messages)

    removed = [False] * len(rest)
    cut = 0

    # Pass 1: drop whole old turns (prompt + answers), keeping the newest turn.
    if rest and rest[0].role.value == "user":
        turn_starts = [i for i, m in enumerate(rest) if m.role.value == "user"]
        kept_turns = 0
        while total > budget and len(turn_starts) - kept_turns >= 2:
            next_start = turn_starts[kept_turns + 1]
            for i in range(cut, next_start):
                removed[i] = True
                total -= counts[i]
            cut = next_start
            kept_turns += 1

    # Pass 2: inside the surviving turn drop the oldest tool round, never a prompt.
    index = cut
    while total > budget and index < len(rest):
        if removed[index] or rest[index].role.value == "user":
            index += 1
            continue
        end = index + 1
        if rest[index].tool_calls:
            while end < len(rest) and rest[end].role.value == "tool":
                end += 1
        for i in range(index, end):
            removed[i] = True
            total -= counts[i]
        index = end

    kept = [m for i, m in enumerate(rest) if not removed[i]]
    # Never start with tool results whose assistant message was trimmed away.
    # 不要以孤立的 tool 结果开头。
    while kept and kept[0].role.value == "tool":
        kept.pop(0)
    return system + kept
