"""Shell-side tool definitions and the remote invocation proxy.

The IDE shell owns file system, PSI and git capabilities; the Core only
schedules them. Every tool whose ``remote`` flag is true is forwarded via the
``tool/invoke`` notification and answered with a ``tool/result`` request.
"""

from typing import Any, Awaitable, Callable, Dict, List

from agent_core.protocol import Permission, ToolCall, ToolDef, ToolResult

InvokeFn = Callable[[ToolCall], Awaitable[ToolResult]]


def _schema(properties: Dict[str, Any], required: List[str] = None) -> Dict[str, Any]:
    return {"type": "object", "properties": properties, "required": required or []}


# Read-only tools plus the edit set; terminal tools arrive with M4/M6.
# 只读工具 + 编辑工具；终端工具随后加入。
SHELL_TOOLS: List[ToolDef] = [
    ToolDef(
        name="read_file",
        description="读取项目文件内容。可选行范围（1 起始，含两端）。返回带行号的文本；超大文件会截断。",
        parameters=_schema(
            {
                "path": {"type": "string", "description": "文件路径，相对项目根或绝对路径"},
                "start_line": {"type": "integer", "minimum": 1, "description": "起始行（可选）"},
                "end_line": {"type": "integer", "minimum": 1, "description": "结束行（可选）"},
            },
            ["path"],
        ),
        permission=Permission.AUTO,
        timeout_sec=15,
    ),
    ToolDef(
        name="list_dir",
        description="列出目录下的文件与子目录（不递归），用于了解项目结构。",
        parameters=_schema(
            {"path": {"type": "string", "description": "目录路径，相对项目根或绝对路径；缺省为项目根"}}
        ),
        permission=Permission.AUTO,
        timeout_sec=15,
    ),
    ToolDef(
        name="search_code",
        description="在项目内按正则表达式搜索文本，返回匹配的文件与行号（默认最多 50 条）。",
        parameters=_schema(
            {
                "pattern": {"type": "string", "description": "正则表达式"},
                "path": {"type": "string", "description": "限定搜索目录（可选）"},
                "max_results": {"type": "integer", "minimum": 1, "maximum": 200,
                                "description": "最多结果数，默认 50"},
            },
            ["pattern"],
        ),
        permission=Permission.AUTO,
        timeout_sec=30,
    ),
    ToolDef(
        name="git_log",
        description="获取指定日期范围内的 Git 提交记录（hash、作者、日期、说明）。",
        parameters=_schema(
            {
                "start_date": {"type": "string", "description": "起始日期 yyyy-MM-dd"},
                "end_date": {"type": "string", "description": "结束日期 yyyy-MM-dd"},
            },
            ["start_date", "end_date"],
        ),
        permission=Permission.AUTO,
        timeout_sec=30,
    ),
    ToolDef(
        name="git_diff",
        description="查看工作区未提交的变更（git diff），可指定路径。",
        parameters=_schema(
            {"path": {"type": "string", "description": "限定文件或目录（可选）"}}
        ),
        permission=Permission.AUTO,
        timeout_sec=30,
    ),
    ToolDef(
        name="write_file",
        description="新建或整体覆盖文件。执行前会向用户展示 diff 确认；文本内容需完整。",
        parameters=_schema(
            {
                "path": {"type": "string", "description": "文件路径，相对项目根或绝对路径"},
                "content": {"type": "string", "description": "完整文件内容"},
            },
            ["path", "content"],
        ),
        permission=Permission.PREVIEW,
        timeout_sec=30,
    ),
    ToolDef(
        name="edit_file",
        description=(
            "在已有文件中做精确文本替换。old_string 必须与文件内容完全一致（含缩进）且唯一，"
            "否则先用 read_file 确认；replace_all=true 时替换全部匹配。"
        ),
        parameters=_schema(
            {
                "path": {"type": "string", "description": "文件路径"},
                "old_string": {"type": "string", "description": "待替换的原文片段"},
                "new_string": {"type": "string", "description": "替换后的新片段"},
                "replace_all": {"type": "boolean", "description": "是否替换全部匹配，默认 false"},
            },
            ["path", "old_string", "new_string"],
        ),
        permission=Permission.PREVIEW,
        timeout_sec=30,
    ),
    ToolDef(
        name="delete_file",
        description="删除文件。执行前需用户确认。",
        parameters=_schema(
            {"path": {"type": "string", "description": "要删除的文件路径"}},
            ["path"],
        ),
        permission=Permission.CONFIRM,
        timeout_sec=30,
    ),
    ToolDef(
        name="revert_file",
        description="撤销本会话中对某个文件做过的最后一次修改（基于内存快照）。",
        parameters=_schema(
            {"path": {"type": "string", "description": "要回退的文件路径"}},
            ["path"],
        ),
        permission=Permission.AUTO,
        timeout_sec=15,
    ),
]


class RemoteToolProxy:
    """Adapts a shell-side tool into a registry handler."""

    def __init__(self, invoke: InvokeFn) -> None:
        self._invoke = invoke

    async def __call__(self, call: ToolCall) -> ToolResult:
        return await self._invoke(call)
