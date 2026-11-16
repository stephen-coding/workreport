package com.workreport.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// diagnostics: the daemon's current errors/warnings for the whole project or one path.
// Only files the IDE has already analysed have highlights, which is exactly the set
// the user is looking at; the payload is JSON so the model can quote file:line.
// diagnostics：返回 daemon 当前的错误/告警（整项目或指定路径）。
// 只有 IDE 已分析过的文件才有高亮；输出 JSON，便于模型精确引用 file:line。
public final class DiagnosticsTools {

    private static final int MAX_ITEMS = 50;

    private final Project project;
    private final FileTools fileTools;

    public DiagnosticsTools(Project project, FileTools fileTools) {
        this.project = project;
        this.fileTools = fileTools;
    }

    public ToolResult diagnostics(JsonObject args) {
        Path root = fileTools.projectRoot();
        if (root == null) {
            return ToolResult.fail("项目根目录不可用");
        }
        Path filter = root;
        if (args.has("path") && !args.get("path").getAsString().isBlank()) {
            filter = fileTools.resolve(args.get("path").getAsString());
            if (filter == null) {
                return ToolResult.fail("路径无效或越出项目根目录");
            }
        }
        boolean errorsOnly = "error".equalsIgnoreCase(stringArg(args, "severity"));
        final Path scope = filter;

        List<JsonObject> found = new ArrayList<>();
        // Reading the markup model of every document must happen on the EDT.
        // 读取各文档的 markup 模型必须在 EDT 上完成。
        ApplicationManager.getApplication().invokeAndWait(() ->
                ApplicationManager.getApplication().runReadAction(() ->
                        collect(root, scope, errorsOnly, found)));

        found.sort(Comparator
                .comparing((JsonObject item) -> item.get("file").getAsString())
                .thenComparingInt(item -> item.get("line").getAsInt()));

        boolean truncated = found.size() > MAX_ITEMS;
        List<JsonObject> items = truncated ? found.subList(0, MAX_ITEMS) : found;

        JsonArray array = new JsonArray();
        for (JsonObject item : items) {
            array.add(item);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("count", items.size());
        payload.addProperty("truncated", truncated);
        payload.add("items", array);
        return ToolResult.ok(payload.toString());
    }

    private void collect(Path root, Path filter, boolean errorsOnly, List<JsonObject> found) {
        FileDocumentManager manager = FileDocumentManager.getInstance();
        // Diagnostics live on documents the editor has loaded; that is the set the
        // user is looking at, and the only set with highlighter markup.
        // 诊断附着在已加载的文档上——这正是用户正在看的文件，也是唯一有高亮的集合。
        Set<Document> visited = new HashSet<>();
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            Document document = editor.getDocument();
            if (!visited.add(document)) {
                continue;
            }
            VirtualFile file = manager.getFile(document);
            if (file == null || !file.isInLocalFileSystem()) {
                continue;
            }
            Path path = Paths.get(file.getPath()).normalize();
            if (!path.startsWith(root) || !path.startsWith(filter)) {
                continue;
            }
            MarkupModel markup = DocumentMarkupModel.forDocument(document, project, false);
            if (markup == null) {
                continue;
            }
            String relative = root.relativize(path).toString().replace('\\', '/');
            for (RangeHighlighter highlighter : markup.getAllHighlighters()) {
                if (!(highlighter instanceof HighlightInfo)) {
                    continue;
                }
                HighlightInfo info = (HighlightInfo) highlighter;
                String severity = labelOf(info.getSeverity());
                if (severity == null || (errorsOnly && !"error".equals(severity))) {
                    continue;
                }
                int offset = Math.max(0, Math.min(info.getStartOffset(), document.getTextLength()));
                int line = document.getLineNumber(offset) + 1;
                int column = offset - document.getLineStartOffset(line - 1) + 1;

                JsonObject item = new JsonObject();
                item.addProperty("file", relative);
                item.addProperty("line", line);
                item.addProperty("column", column);
                item.addProperty("severity", severity);
                item.addProperty("message", info.getDescription());
                item.addProperty("source", info.getInspectionToolId() == null
                        ? "daemon" : info.getInspectionToolId());
                found.add(item);
            }
        }
    }

    // ERROR(400) > WARNING(300) > INFO(200) > WEAK_WARNING(100); info is noise here.
    // 严重度按数值比较；info / weak warning 不进结果，避免淹没真实问题。
    private String labelOf(HighlightSeverity severity) {
        if (severity == null) {
            return null;
        }
        if (severity.compareTo(HighlightSeverity.ERROR) >= 0) {
            return "error";
        }
        if (severity.compareTo(HighlightSeverity.WARNING) >= 0) {
            return "warning";
        }
        return null;
    }

    private String stringArg(JsonObject args, String key) {
        return args.has(key) ? args.get(key).getAsString() : "";
    }
}
