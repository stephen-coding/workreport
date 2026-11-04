"""Built-in skill tools (skill_list / skill_load) plus auto-matching helpers."""

from typing import Any, Dict, List

from agent_core.protocol import ToolCall, ToolDef, ToolResult
from agent_core.skills.loader import SkillLoader
from agent_core.tools.registry import ToolRegistry

SKILL_TOOLS: List[ToolDef] = [
    ToolDef(
        name="skill_list",
        description="列出当前可用的 Skill（名称、说明、触发场景）。",
        parameters={"type": "object", "properties": {}, "required": []},
        permission="auto",
        remote=False,
    ),
    ToolDef(
        name="skill_load",
        description="按名称加载 Skill 的完整指令正文，按其中的步骤执行任务。",
        parameters={
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Skill 名称，如 daily-report"},
            },
            "required": ["name"],
        },
        permission="auto",
        remote=False,
    ),
]


class SkillService:
    def __init__(self, loader: SkillLoader) -> None:
        self.loader = loader

    def register_tools(self, registry: ToolRegistry) -> None:
        handlers = {
            "skill_list": self._list_handler,
            "skill_load": self._load_handler,
        }
        for tool_def in SKILL_TOOLS:
            registry.register(tool_def, handlers.get(tool_def.name))

    def instruction_for(self, user_input: str, skill_name: str = "") -> str:
        skill = self.loader.get(skill_name) if skill_name else self.loader.match(user_input)
        return skill.render() if skill else ""

    async def _list_handler(self, call: ToolCall) -> ToolResult:
        skills = self.loader.list_skills()
        if not skills:
            return ToolResult(tool_call_id=call.id, ok=True, content="（没有可用 Skill）")
        lines = ["## %s [%s]\n%s\n场景: %s" % (
            skill.name, skill.scope, skill.description, "、".join(skill.scenarios))
            for skill in skills]
        return ToolResult(tool_call_id=call.id, ok=True, content="\n\n".join(lines))

    async def _load_handler(self, call: ToolCall) -> ToolResult:
        args: Dict[str, Any] = call.arguments or {}
        name = (args.get("name") or "").strip()
        if not name:
            return ToolResult(tool_call_id=call.id, ok=False, error="缺少参数 name")
        skill = self.loader.get(name)
        if skill is None:
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="未知 Skill: %s" % name)
        return ToolResult(tool_call_id=call.id, ok=True, content=skill.render())
