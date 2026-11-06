package com.workreport.agent.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// Turns picked files into prompt text for the Core.
// Text files are inlined (bounded by size/char budgets); binaries and huge files keep only
// their absolute path so the agent can still read them with its own tools.
// 把用户选中的文件转成 Core 能理解的提示词文本：文本文件内联（有体积与字数上限），
// 二进制/超大文件只保留绝对路径，交给 Agent 的 read_file 工具按需读取。
public final class ChatAttachment {

    // Hard cap on how many files one message may carry.
    // 单条消息最多携带的附件数量。
    public static final int MAX_FILES = 8;

    private static final int MAX_FILE_CHARS = 40_000;
    private static final int MAX_TOTAL_CHARS = 120_000;
    private static final long MAX_INLINE_BYTES = 512L * 1024L;
    private static final int BINARY_PROBE = 4096;

    private ChatAttachment() {
    }

    // Build the final user message: typed text first, then one block per attachment.
    // 组装最终发送给 Core 的消息：用户输入在前，附件内容按块追加在后。
    public static String buildPrompt(String userText, List<Path> files, String projectRoot) {
        StringBuilder prompt = new StringBuilder(userText == null ? "" : userText);
        if (files == null || files.isEmpty()) {
            return prompt.toString();
        }
        prompt.append("\n\n---\n以下是我附加的文件内容，请把它们作为上下文：\n");
        int budget = MAX_TOTAL_CHARS;
        for (int index = 0; index < files.size(); index++) {
            Path file = files.get(index);
            prompt.append("\n【附件 ").append(index + 1).append('/').append(files.size()).append("】")
                    .append(displayName(file, projectRoot)).append('\n');
            if (budget <= 0) {
                prompt.append("（已达单条消息的上下文上限，未内联）\n");
                continue;
            }
            if (Files.isDirectory(file)) {
                prompt.append("（这是一个目录，未内联；需要时用 list_dir 查看、read_file 逐个读取：")
                        .append(file.toAbsolutePath()).append("）\n");
                continue;
            }
            String body = readInline(file);
            if (body == null) {
                prompt.append("（二进制或超过 512 KB，未内联，需要时用 read_file 读取：")
                        .append(file.toAbsolutePath()).append("）\n");
                continue;
            }
            int limit = Math.min(body.length(), Math.min(MAX_FILE_CHARS, budget));
            String content = body.substring(0, limit);
            budget -= content.length();
            prompt.append("```").append(fenceLanguage(file)).append('\n').append(content);
            if (!content.endsWith("\n")) {
                prompt.append('\n');
            }
            prompt.append("```\n");
            if (limit < body.length()) {
                prompt.append("（以上为前 ").append(limit).append(" 个字符，原文件共 ")
                        .append(body.length()).append(" 个字符）\n");
            }
        }
        return prompt.toString();
    }

    // Project-relative path when possible, absolute otherwise (used inside the prompt).
    // 提示词里展示项目相对路径，项目外的文件退回绝对路径。
    public static String displayName(Path file, String projectRoot) {
        String relative = relativePath(file, projectRoot);
        return relative != null ? relative : normalize(file.toAbsolutePath().toString());
    }

    private static String relativePath(Path file, String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return null;
        }
        try {
            Path root = Path.of(projectRoot).toAbsolutePath().normalize();
            Path target = file.toAbsolutePath().normalize();
            if (target.startsWith(root) && !target.equals(root)) {
                return normalize(root.relativize(target).toString());
            }
        } catch (RuntimeException ignored) {
            // Malformed path (e.g. different drive) — fall back to the absolute form.
        }
        return null;
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    private static String readInline(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_INLINE_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (isBinary(bytes)) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_PROBE);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    // Fence hint so the model knows which language the snippet is.
    // 代码块语言标注，帮助模型正确理解附件内容。
    private static String fenceLanguage(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "java" -> "java";
            case "kt", "kts" -> "kotlin";
            case "py" -> "python";
            case "ts", "tsx" -> "typescript";
            case "js", "jsx", "mjs", "cjs" -> "javascript";
            case "json" -> "json";
            case "xml" -> "xml";
            case "html", "htm" -> "html";
            case "css", "scss", "less" -> "css";
            case "yml", "yaml" -> "yaml";
            case "sh", "ps1", "bat" -> "bash";
            case "sql" -> "sql";
            case "md", "markdown" -> "markdown";
            case "go" -> "go";
            case "rs" -> "rust";
            case "c", "h" -> "c";
            case "cpp", "cc", "cxx", "hpp" -> "cpp";
            case "cs" -> "csharp";
            case "php" -> "php";
            case "rb" -> "ruby";
            case "gradle" -> "groovy";
            default -> "";
        };
    }
}
