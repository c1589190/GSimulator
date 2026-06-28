package com.gsim.skill.tool;

import com.gsim.llm.EmbeddingClient;
import com.gsim.skill.SkillIndex;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 语义搜索 Skill — 基于 embedding 向量做余弦相似度搜索。
 * 若无 EmbeddingClient，降级为关键词匹配。
 */
public final class SkillSearchTool implements AgentTool {

    private final SkillIndex index;
    private final EmbeddingClient embeddingClient;

    public SkillSearchTool(SkillIndex index, EmbeddingClient embeddingClient) {
        this.index = index;
        this.embeddingClient = embeddingClient;
    }

    @Override
    public String name() { return "skill_search"; }

    @Override
    public String description() {
        return "语义搜索 Skill：根据查询文本找到最相关的 Skill。"
                + "参数: query (搜索文本, 必填), topK (返回数量, 默认5)。"
                + (embeddingClient != null && embeddingClient.isConfigured()
                        ? " 当前使用 embedding 向量语义搜索。"
                        : " 当前降级为关键词匹配。配置 EMBEDDING_BASE_URL 环境变量以启用语义搜索。");
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "搜索查询文本"),
                        "topK", Map.of("type", "integer", "description", "返回结果数量，默认 5，最大 20")
                ),
                "required", List.of("query")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String query = call.param("query", "").trim();
        if (query.isEmpty()) return ToolResult.fail(name(), "query 不能为空");

        int topK = parseInt(call.param("topK"), 5);
        topK = Math.min(Math.max(topK, 1), 20);

        if (index.count() == 0) {
            return ToolResult.fail(name(),
                    "暂无已索引的 Skill。请先用 skill_index 工具索引已有的 Skill。");
        }

        List<SkillIndex.SearchResult> results;

        // 尝试 embedding 搜索
        if (embeddingClient != null && embeddingClient.isConfigured()) {
            try {
                float[] queryVec = embeddingClient.embed(query);
                results = index.search(queryVec, topK);
            } catch (IOException e) {
                // embedding 失败 → 降级
                results = index.keywordSearch(query, topK);
            }
        } else {
            results = index.keywordSearch(query, topK);
        }

        if (results.isEmpty()) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("无结果", "", "未找到匹配的 Skill", 0)));
        }

        List<ToolResult.Item> items = new ArrayList<>();
        for (var r : results) {
            String snippet = String.format("score=%.3f | %s", r.score(), r.summary());
            items.add(new ToolResult.Item(r.name(), r.id(), snippet, r.score()));
        }

        return ToolResult.ok(name(), items);
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
}
