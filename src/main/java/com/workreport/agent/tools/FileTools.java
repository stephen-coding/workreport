package com.workreport.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

// read_file / list_dir implementations.
// read_file / list_dir 工具实现。
public final class FileTools {

    private static final long MAX_FILE_BYTES = 512 * 1024;
    private static final int MAX_LIST_ENTRIES = 300;

    private final Project project;

    public FileTools(Project project) {
        this.project = project;
    }

    public ToolResult readFile(JsonObject args) {
        if (!args.has("path")) {
            return ToolResult.fail("缺少参数 path");
        }
        Path path = resolve(args.get("path").getAsString());
        if (path == null) {
            return ToolResult.fail("路径无效或越出项目根目录");
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.fail("文件不存在: " + path);
        }
        try {
            long size = Files.size(path);
            if (size > MAX_FILE_BYTES) {
                return ToolResult.fail("文件过大（" + size + " 字节），请用 start_line/end_line 指定范围");
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return ToolResult.ok("（空文件）");
            }
            int start = args.has("start_line") ? Math.max(1, args.get("start_line").getAsInt()) : 1;
            int end = args.has("end_line")
                    ? Math.min(lines.size(), args.get("end_line").getAsInt())
                    : lines.size();
            if (start > lines.size() || start > end) {
                return ToolResult.ok("（无匹配行；文件共 " + lines.size() + " 行）");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = start; i <= end; i++) {
                sb.append(i).append("→").append(lines.get(i - 1)).append('\n');
            }
            return ToolResult.ok(sb.toString());
        } catch (IOException e) {
            return ToolResult.fail("读取失败: " + e.getMessage());
        }
    }

    public ToolResult listDir(JsonObject args) {
        Path dir = args.has("path") ? resolve(args.get("path").getAsString()) : projectRoot();
        if (dir == null) {
            return ToolResult.fail("路径无效或越出项目根目录");
        }
        if (!Files.isDirectory(dir)) {
            return ToolResult.fail("目录不存在: " + dir);
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<String> entries = new ArrayList<>();
            stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .limit(MAX_LIST_ENTRIES)
                    .forEach(p -> entries.add(
                            Files.isDirectory(p) ? p.getFileName() + "/" : p.getFileName().toString()));
            return ToolResult.ok(entries.isEmpty() ? "（空目录）" : String.join("\n", entries));
        } catch (IOException e) {
            return ToolResult.fail("列目录失败: " + e.getMessage());
        }
    }

    // Resolve a user-supplied path against the project root; reject escapes.
    // 将用户/模型给出的路径解析到项目根下，拒绝越界。
    public Path resolve(String rawPath) {
        Path base = projectRoot();
        if (base == null || rawPath == null || rawPath.isBlank()) {
            return null;
        }
        Path candidate = Paths.get(rawPath);
        if (!candidate.isAbsolute()) {
            candidate = base.resolve(rawPath);
        }
        candidate = candidate.normalize();
        return candidate.startsWith(base) ? candidate : null;
    }

    public Path projectRoot() {
        String basePath = project.getBasePath();
        return basePath == null ? null : Paths.get(basePath).toAbsolutePath().normalize();
    }
}
