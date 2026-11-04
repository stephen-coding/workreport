package com.workreport.agent.tools;

// Result of one shell-side tool execution.
// Shell 端单个工具的执行结果。
public record ToolResult(boolean ok, String content, String error) {

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, null, error);
    }
}
