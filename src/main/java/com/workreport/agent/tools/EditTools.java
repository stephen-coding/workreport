package com.workreport.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.workreport.agent.ui.DiffConfirmCard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// write_file / edit_file / delete_file / revert_file implementations.
// Every mutation captures a snapshot first so revert_file can undo it.
// write_file / edit_file / delete_file / revert_file 实现；
// 所有写操作先做快照，支持 revert_file 回退。
public final class EditTools {

    private final Project project;
    private final FileTools fileTools;
    private final FileSnapshots snapshots = new FileSnapshots();

    public EditTools(Project project, FileTools fileTools) {
        this.project = project;
        this.fileTools = fileTools;
    }

    public ToolResult writeFile(JsonObject args, boolean needsApproval) {
        if (!args.has("path") || !args.has("content")) {
            return ToolResult.fail("缺少参数 path / content");
        }
        Path path = fileTools.resolve(args.get("path").getAsString());
        if (path == null) {
            return ToolResult.fail("路径无效或越出项目根目录");
        }
        String newContent = args.get("content").getAsString();
        flushDocuments();
        // Read through the decoder so a GBK/UTF-16 file is not shown as "new".
        // 用解码器读取，避免 GBK/UTF-16 文件被当成空文件。
        FileText.Decoded previous = readDecodedOrNull(path);
        String oldContent = previous == null ? "" : previous.text();

        if (needsApproval && !DiffConfirmCard.confirm(project, path, oldContent, newContent)) {
            return ToolResult.fail("用户拒绝：未写入 " + relative(path));
        }

        snapshots.capture(path, oldContent);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            FileText.write(path, newContent, previous);
            refreshVfs(path);
            return ToolResult.ok("已写入 " + relative(path)
                    + "（" + newContent.length() + " 字符" + encodingNote(previous) + "）");
        } catch (IOException e) {
            return ToolResult.fail("写入失败: " + e.getMessage());
        }
    }

    public ToolResult editFile(JsonObject args, boolean needsApproval) {
        if (!args.has("path") || !args.has("old_string") || !args.has("new_string")) {
            return ToolResult.fail("缺少参数 path / old_string / new_string");
        }
        Path path = fileTools.resolve(args.get("path").getAsString());
        if (path == null) {
            return ToolResult.fail("路径无效或越出项目根目录");
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.fail("文件不存在: " + relative(path));
        }
        String oldText = args.get("old_string").getAsString();
        String newText = args.get("new_string").getAsString();
        if (oldText.isEmpty()) {
            return ToolResult.fail("old_string 不能为空");
        }
        boolean replaceAll = args.has("replace_all") && args.get("replace_all").getAsBoolean();

        flushDocuments();
        FileText.Decoded previous = readDecodedOrNull(path);
        String oldContent = previous == null ? "" : previous.text();
        int first = oldContent.indexOf(oldText);
        if (first < 0) {
            return ToolResult.fail("未找到 old_string，请先用 read_file 确认原文（注意缩进与换行）");
        }
        if (!replaceAll && oldContent.indexOf(oldText, first + 1) >= 0) {
            return ToolResult.fail("old_string 匹配到多处，请提供更长的上下文或设置 replace_all=true");
        }

        String newContent = replaceAll
                ? oldContent.replace(oldText, newText)
                : oldContent.substring(0, first) + newText + oldContent.substring(first + oldText.length());

        if (needsApproval && !DiffConfirmCard.confirm(project, path, oldContent, newContent)) {
            return ToolResult.fail("用户拒绝：未修改 " + relative(path));
        }

        snapshots.capture(path, oldContent);
        try {
            FileText.write(path, newContent, previous);
            refreshVfs(path);
            return ToolResult.ok("已修改 " + relative(path)
                    + (replaceAll ? "（替换全部匹配" : "（1 处")
                    + (previous != null && previous.isPlainUtf8() ? "）" : encodingNote(previous) + "）"));
        } catch (IOException e) {
            return ToolResult.fail("写入失败: " + e.getMessage());
        }
    }

    public ToolResult deleteFile(JsonObject args, boolean needsApproval) {
        if (!args.has("path")) {
            return ToolResult.fail("缺少参数 path");
        }
        Path path = fileTools.resolve(args.get("path").getAsString());
        if (path == null) {
            return ToolResult.fail("路径无效或越出项目根目录");
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.fail("文件不存在: " + relative(path));
        }
        flushDocuments();
        String oldContent = readOrEmpty(path);

        if (needsApproval && !DiffConfirmCard.confirm(project, path, oldContent, "")) {
            return ToolResult.fail("用户拒绝：未删除 " + relative(path));
        }

        snapshots.capture(path, oldContent);
        try {
            Files.delete(path);
            refreshVfs(path);
            return ToolResult.ok("已删除 " + relative(path) + "（可用 revert_file 恢复）");
        } catch (IOException e) {
            return ToolResult.fail("删除失败: " + e.getMessage());
        }
    }

    public ToolResult revertFile(JsonObject args) {
        if (!args.has("path")) {
            return ToolResult.fail("缺少参数 path");
        }
        Path path = fileTools.resolve(args.get("path").getAsString());
        if (path == null) {
            return ToolResult.fail("路径无效或越出项目根目录");
        }
        if (!snapshots.has(path)) {
            return ToolResult.fail("没有可回退的快照: " + relative(path));
        }
        String previous = snapshots.pop(path);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            // edit_file kept the original encoding, so the snapshot can reuse it.
            // 编辑时已保持原编码，回退直接沿用当前文件的编码。
            FileText.write(path, previous, readDecodedOrNull(path));
            refreshVfs(path);
            return ToolResult.ok("已回退 " + relative(path) + " 到上一次修改前");
        } catch (IOException e) {
            return ToolResult.fail("回退失败: " + e.getMessage());
        }
    }

    private FileText.Decoded readDecodedOrNull(Path path) {
        try {
            return Files.isRegularFile(path) ? FileText.read(path) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private String encodingNote(FileText.Decoded decoded) {
        return decoded == null || decoded.isPlainUtf8() ? "" : "，编码 " + decoded.label();
    }

    private String readOrEmpty(Path path) {
        FileText.Decoded decoded = readDecodedOrNull(path);
        return decoded == null ? "" : decoded.text();
    }

    // Persist dirty documents so the agent never overwrites unsaved user edits.
    // 先把未保存的文档落盘，避免覆盖用户正在编辑的内容。
    private void flushDocuments() {
        ApplicationManager.getApplication().invokeAndWait(
                () -> FileDocumentManager.getInstance().saveAllDocuments());
    }

    private void refreshVfs(Path path) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile());
            if (file != null) {
                file.refresh(false, false);
            }
        });
    }

    private String relative(Path path) {
        Path root = fileTools.projectRoot();
        if (root != null && path.startsWith(root)) {
            return root.relativize(path).toString().replace('\\', '/');
        }
        return path.toString();
    }
}
