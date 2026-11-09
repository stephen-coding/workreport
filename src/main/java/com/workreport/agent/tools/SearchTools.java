package com.workreport.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

// search_code implementation: regex text search with a result cap.
// search_code 实现：正则文本搜索，带结果上限。
public final class SearchTools {

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final long MAX_SCAN_BYTES = 1024 * 1024;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", ".gradle", "target", "build", "dist", "out", "node_modules");
    private static final Set<String> SKIP_EXTENSIONS = Set.of(
            "jar", "class", "png", "jpg", "jpeg", "gif", "ico", "svg", "zip", "gz",
            "exe", "dll", "so", "dylib", "bin", "pdf", "woff", "woff2", "ttf",
            "mp4", "mp3", "lock");

    private final Project project;
    private final FileTools fileTools;

    public SearchTools(Project project, FileTools fileTools) {
        this.project = project;
        this.fileTools = fileTools;
    }

    public ToolResult searchCode(JsonObject args) {
        if (!args.has("pattern")) {
            return ToolResult.fail("缺少参数 pattern");
        }
        Pattern regex;
        try {
            regex = Pattern.compile(args.get("pattern").getAsString());
        } catch (PatternSyntaxException e) {
            return ToolResult.fail("正则无效: " + e.getMessage());
        }
        int max = args.has("max_results")
                ? Math.max(1, Math.min(200, args.get("max_results").getAsInt()))
                : DEFAULT_MAX_RESULTS;
        Path root = args.has("path") ? fileTools.resolve(args.get("path").getAsString())
                : fileTools.projectRoot();
        if (root == null || (!Files.isDirectory(root) && !Files.isRegularFile(root))) {
            return ToolResult.fail("搜索路径不存在");
        }
        if (Files.isRegularFile(root)) {
            // The model often points at one file; search it instead of failing.
            // 模型常直接把文件当搜索路径，这里直接搜该文件。
            List<String> results = new ArrayList<>();
            scanFile(root, root.getFileName().toString(), regex, max, results);
            return ToolResult.ok(results.isEmpty() ? "（无匹配结果）" : String.join("\n", results));
        }

        List<String> results = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            Iterator<Path> iterator = walk.iterator();
            while (iterator.hasNext() && results.size() < max) {
                Path file = iterator.next();
                if (Files.isDirectory(file) || shouldSkip(file)) {
                    continue;
                }
                scanFile(file, root.relativize(file).toString(), regex, max, results);
            }
        } catch (IOException e) {
            return ToolResult.fail("搜索失败: " + e.getMessage());
        }
        return ToolResult.ok(results.isEmpty() ? "（无匹配结果）" : String.join("\n", results));
    }

    // Decode through FileText so GBK / UTF-16 logs are searchable too.
    // 用 FileText 解码，GBK / UTF-16 日志也能搜到。
    private void scanFile(Path file, String display, Pattern regex, int max, List<String> results) {
        List<String> lines;
        try {
            if (Files.size(file) > MAX_SCAN_BYTES || isBinaryName(file)) {
                return;
            }
            lines = FileText.splitLines(FileText.read(file).text());
        } catch (IOException ignored) {
            return; // unreadable file, skip
        }
        for (int i = 0; i < lines.size() && results.size() < max; i++) {
            if (regex.matcher(lines.get(i)).find()) {
                results.add(display + ":" + (i + 1) + ": " + lines.get(i).trim());
            }
        }
    }

    private boolean shouldSkip(Path file) {
        for (Path part : file) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return isBinaryName(file);
    }

    private boolean isBinaryName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SKIP_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }
}
