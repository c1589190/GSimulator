package com.gsim.agent.tools.doc;

import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.doc.DocCacheManager;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.doc.Document;
import java.util.List;
import java.util.Map;

/**
 * 读取文档 — 支持常规文档 ID 和 {@code @cache:id} 虚拟文档引用。
 *
 * <p>limit 设为 -1 或 0 表示读取全文（无行数限制）。
 */
public final class DocReadTool implements AgentTool {

    private final DocStore store;
    private final DocCacheManager cacheManager;

    /**
     * 创建文档读取工具实例。
     *
     * @param store         文档存储
     * @param cacheManager  缓存管理器，用于支持 @cache: 虚拟文档引用
     */
    public DocReadTool(DocStore store, DocCacheManager cacheManager) {
        this.store = store;
        this.cacheManager = cacheManager;
    }

    @Override
    public String name() {
        return "doc_read";
    }

    @Override
    public String description() {
        return "读取文档或缓存内容。参数: docId (必填，支持 @cache:id 虚拟文档引用), "
                + "offset (起始行号 0-based, 默认 0), "
                + "limit (读取行数, 默认 200; -1 或 0 = 读取全文)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "docId", Map.of("type", "string", "description", "文档 ID 或 @cache:id 虚拟文档引用"),
                                "offset", Map.of("type", "integer", "description", "起始行号 (0-based)，默认 0"),
                                "limit", Map.of("type", "integer", "description", "读取行数，默认 200；-1 或 0 = 读取全文")),
                "required", List.of("docId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String docId = call.param("docId", "").trim();
        if (docId.isEmpty()) return ToolResult.fail(name(), "docId 不能为空");

        String title;
        String content;

        // @cache: 虚拟文档
        String cached = cacheManager.resolveDocId(docId);
        if (cached != null) {
            content = cached;
            title = docId; // 直接显示 @cache:id
        } else {
            // 常规 docId 校验
            if (!docId.matches("^[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*$")) {
                return ToolResult.fail(name(), "docId 只能包含字母、数字、连字符、下划线，或以 @cache: 开头");
            }
            Document doc = store.get(docId);
            if (doc == null) return ToolResult.fail(name(), "文档不存在: " + docId);
            content = doc.content();
            title = doc.title() + " (" + docId + ")";
        }

        String[] lines = content.split("\n", -1);
        int totalLines = lines.length;

        int offset = parseInt(call.param("offset"), 0);
        int rawLimit = parseInt(call.param("limit"), 200);
        // -1 或 0 = 读取全文
        int limit = (rawLimit <= 0) ? totalLines : rawLimit;

        int start = Math.max(0, Math.min(offset, totalLines));
        int end = Math.min(start + limit, totalLines);

        boolean readingFull = (rawLimit <= 0) && start == 0;

        // 格式化显示（带行号）
        StringBuilder output = new StringBuilder();
        for (int i = start; i < end; i++) {
            output.append(String.format("%6d| ", i)).append(lines[i]).append("\n");
        }

        String snippet = output.toString();
        if (snippet.isEmpty()) snippet = "(文档为空)";

        String rangeInfo =
                readingFull ? "全文 " + totalLines + " 行" : "lines " + start + "-" + (end - 1) + " / " + totalLines;
        return ToolResult.ok(name(), List.of(new ToolResult.Item(title + " " + rangeInfo, docId, snippet, 1.0)));
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
