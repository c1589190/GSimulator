package com.gsim.doc.tool;

import com.gsim.doc.DocStore;
import com.gsim.doc.Document;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 分段读取文档 — 按行号 offset/limit 读取，带行号输出。
 */
public final class DocReadTool implements AgentTool {

    private final DocStore store;

    public DocReadTool(DocStore store) {
        this.store = store;
    }

    @Override
    public String name() { return "doc_read"; }

    @Override
    public String description() {
        return "分段读取文档内容。参数: docId (必填), offset (起始行号 0-based, 默认 0), "
                + "limit (读取行数, 默认 200, 最大 500)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "docId", Map.of("type", "string", "description", "文档 ID"),
                        "offset", Map.of("type", "integer", "description", "起始行号 (0-based)，默认 0"),
                        "limit", Map.of("type", "integer", "description", "读取行数，默认 200，最大 500")
                ),
                "required", List.of("docId")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String docId = call.param("docId", "").trim();
        if (docId.isEmpty()) return ToolResult.fail(name(), "docId 不能为空");

        if (!docId.matches("^[a-zA-Z0-9_-]+$")) {
            return ToolResult.fail(name(), "docId 只能包含字母、数字、连字符");
        }

        Document doc = store.get(docId);
        if (doc == null) return ToolResult.fail(name(), "文档不存在: " + docId);

        String content = doc.content();
        String[] lines = content.split("\n", -1);
        int totalLines = lines.length;

        int offset = parseInt(call.param("offset"), 0);
        int limit = Math.min(parseInt(call.param("limit"), 200), 500);

        int start = Math.max(0, Math.min(offset, totalLines));
        int end = Math.min(start + limit, totalLines);

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(String.format("%6d| ", i)).append(lines[i]).append("\n");
        }

        String snippet = sb.toString();
        if (snippet.isEmpty()) snippet = "(文档为空)";

        String title = doc.title() + " (" + docId + ") lines " + start + "-"
                + (end - 1) + " / " + totalLines;
        return ToolResult.ok(name(), List.of(new ToolResult.Item(title, docId, snippet, 1.0)));
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
}
