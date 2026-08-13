package com.gsim.doc.tool;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.doc.DocStore;
import com.gsim.doc.Document;
import com.gsim.embedding.EmbeddingClient;
import com.gsim.skill.SkillIndex;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 搜索文档 — 基于 embedding 向量语义搜索（含关键词降级）。
 * 复用现有 SkillIndex 作为索引引擎。
 */
public final class DocSearchTool implements AgentTool {

    private final DocStore store;
    private final SkillIndex index;
    private final EmbeddingClient embeddingClient;

    /**
     * 创建文档搜索工具实例。
     *
     * @param store           文档存储
     * @param index           语义索引引擎（复用 SkillIndex）
     * @param embeddingClient embedding 向量计算客户端
     */
    public DocSearchTool(DocStore store, SkillIndex index, EmbeddingClient embeddingClient) {
        this.store = store;
        this.index = index;
        this.embeddingClient = embeddingClient;
    }

    @Override
    public String name() {
        return "doc_search";
    }

    @Override
    public String description() {
        return "搜索文档：根据查询文本找到最相关的文档。"
                + "参数: query (搜索文本, 必填), topK (返回数量, 默认5, 最大20)。"
                + (embeddingClient != null && embeddingClient.isConfigured()
                        ? " 当前使用 embedding 向量语义搜索。"
                        : " 当前降级为关键词匹配。");
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "query", Map.of("type", "string", "description", "搜索查询文本"),
                                "topK", Map.of("type", "integer", "description", "返回数量，默认 5，最大 20")),
                "required", List.of("query"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String query = call.param("query", "").trim();
        if (query.isEmpty()) return ToolResult.fail(name(), "query 不能为空");

        int topK = parseInt(call.param("topK"), 5);
        topK = Math.min(Math.max(topK, 1), 20);

        // 无索引时降级为关键词搜索
        if (index == null || index.count() == 0) {
            var allDocs = store.list(null, null);
            var matched = new ArrayList<Document>();
            var lowerQ = query.toLowerCase();
            for (var doc : allDocs) {
                if (doc.title().toLowerCase().contains(lowerQ)
                        || doc.id().toLowerCase().contains(lowerQ)
                        || (doc.content() != null && doc.content().toLowerCase().contains(lowerQ))) {
                    matched.add(doc);
                }
            }
            if (matched.isEmpty()) {
                return ToolResult.ok(name(), List.of(new ToolResult.Item("无结果", "", "未找到匹配的文档（关键词模式）", 0)));
            }
            var items = new ArrayList<ToolResult.Item>();
            int count = 0;
            for (var doc : matched) {
                if (count >= topK) break;
                String typeStr = doc.type().key();
                String snippet = doc.content() != null && doc.content().length() > 100
                        ? doc.content().substring(0, 100) + "..."
                        : (doc.content() != null ? doc.content() : "");
                items.add(new ToolResult.Item(
                        doc.title(), doc.id(), "score=0.5 | type=" + typeStr + " | " + snippet, 0.5));
                count++;
            }
            return ToolResult.ok(name(), items);
        }

        List<com.gsim.skill.SkillIndex.SearchResult> results;
        if (embeddingClient != null && embeddingClient.isConfigured()) {
            try {
                float[] queryVec = embeddingClient.embed(query);
                results = index.search(queryVec, topK);
            } catch (IOException e) {
                results = index.keywordSearch(query, topK);
            }
        } else {
            results = index.keywordSearch(query, topK);
        }

        if (results.isEmpty()) {
            // 索引搜索无结果时 fallback 到全文搜索
            return fallbackKeywordSearch(query, topK);
        }

        List<ToolResult.Item> items = new ArrayList<>();
        for (var r : results) {
            Document doc = store.get(r.id());
            String typeStr = doc != null ? doc.type().key() : "?";
            String snippet = String.format("score=%.3f | type=%s | %s", r.score(), typeStr, r.summary());
            items.add(new ToolResult.Item(r.name(), r.id(), snippet, r.score()));
        }

        return ToolResult.ok(name(), items);
    }

    /**
     * 全文搜索 fallback：遍历所有文档按 title/id/content 做关键词匹配。
     */
    private ToolResult fallbackKeywordSearch(String query, int topK) {
        var allDocs = store.list(null, null);
        var matched = new ArrayList<Document>();
        var lowerQ = query.toLowerCase();
        for (var doc : allDocs) {
            if (doc.title().toLowerCase().contains(lowerQ)
                    || doc.id().toLowerCase().contains(lowerQ)
                    || (doc.content() != null && doc.content().toLowerCase().contains(lowerQ))) {
                matched.add(doc);
            }
        }
        if (matched.isEmpty()) {
            return ToolResult.ok(name(), List.of(new ToolResult.Item("无结果", "", "未找到匹配的文档", 0)));
        }
        var items = new ArrayList<ToolResult.Item>();
        int count = 0;
        for (var doc : matched) {
            if (count >= topK) break;
            String typeStr = doc.type().key();
            String snippet = doc.content() != null && doc.content().length() > 100
                    ? doc.content().substring(0, 100) + "..."
                    : (doc.content() != null ? doc.content() : "");
            items.add(new ToolResult.Item(doc.title(), doc.id(), "score=0.5 | type=" + typeStr + " | " + snippet, 0.5));
            count++;
        }
        return ToolResult.ok(name(), items);
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
