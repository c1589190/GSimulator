package com.gsim.doc.tool;

import com.gsim.doc.DocStore;
import com.gsim.doc.Document;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 修改文档 — 支持全文替换、尾部追加、行范围覆盖三种模式。
 */
public final class DocWriteTool implements AgentTool {

    private final DocStore store;
    private final com.gsim.doc.DocCacheManager cacheManager;

    public DocWriteTool(DocStore store, com.gsim.doc.DocCacheManager cacheManager) {
        this.store = store;
        this.cacheManager = cacheManager;
    }

    @Override
    public String name() { return "doc_write"; }

    @Override
    public String description() {
        return "修改文档内容。参数: docId (必填), content (要写入的文本, 必填), "
                + "mode (replace 全文替换 / append 尾部追加 / line_range 行范围覆盖, 默认 replace), "
                + "line_start / line_end (mode=line_range 时的行范围, 0-based, start 含 end 不含), "
                + "title (可选, 修改标题), tags (可选, 逗号分隔修改标签)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "docId", Map.of("type", "string", "description", "文档 ID"),
                        "content", Map.of("type", "string", "description", "要写入的文本"),
                        "mode", Map.of("type", "string",
                                "description", "写入模式: replace, append, line_range。默认 replace"),
                        "line_start", Map.of("type", "integer",
                                "description", "行范围起始行 (0-based, 含)。仅 mode=line_range"),
                        "line_end", Map.of("type", "integer",
                                "description", "行范围结束行 (0-based, 不含)。仅 mode=line_range"),
                        "title", Map.of("type", "string", "description", "新标题（可选）"),
                        "tags", Map.of("type", "string", "description", "新标签，逗号分隔（可选）")
                ),
                "required", List.of("docId", "content")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String docId = call.param("docId", "").trim();
        String content = call.param("content", "").trim();
        String mode = call.param("mode", "replace").trim();
        String newTitle = call.param("title", "").trim();
        String tagsStr = call.param("tags", "").trim();

        if (docId.isEmpty()) return ToolResult.fail(name(), "docId 不能为空");

        // 解析 @cache: 引用
        content = cacheManager.resolve(content);

        Document old = store.get(docId);
        if (old == null) return ToolResult.fail(name(), "文档不存在: " + docId);

        try {
            String newContent = switch (mode) {
                case "append" -> old.content() + "\n" + content;
                case "line_range" -> {
                    int start = parseInt(call.param("line_start"), 0);
                    int end = parseInt(call.param("line_end"), -1);
                    String[] lines = old.content().split("\n", -1);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < start && i < lines.length; i++) {
                        sb.append(lines[i]).append("\n");
                    }
                    sb.append(content);
                    if (end >= 0 && end < lines.length) {
                        sb.append("\n");
                        for (int i = end; i < lines.length; i++) {
                            sb.append(lines[i]);
                            if (i < lines.length - 1) sb.append("\n");
                        }
                    }
                    yield sb.toString();
                }
                default -> content;
            };

            Document updated = store.updateContent(docId, newContent);
            if (updated == null) {
                return ToolResult.fail(name(), "更新失败: " + docId);
            }

            // 同时更新元数据
            if (!newTitle.isEmpty() || !tagsStr.isEmpty()) {
                List<String> tags = tagsStr.isEmpty() ? old.tags()
                        : List.of(tagsStr.split("\\s*,\\s*"));
                store.updateMeta(docId,
                        newTitle.isEmpty() ? old.title() : newTitle, tags);
            }

            return ToolResult.ok(name(), List.of(new ToolResult.Item(
                    updated.title(), docId,
                    "已更新: mode=" + mode + " v" + (old.version()) + "→v" + (updated.version()),
                    1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "写入失败: " + e.getMessage());
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
}
