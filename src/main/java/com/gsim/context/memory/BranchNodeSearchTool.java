package com.gsim.context.memory;

import com.gsim.context.summary.NodeSummary;
import com.gsim.context.summary.NodeSummaryStore;
import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.*;

/**
 * branch_node_search — 搜索节点概要和消息。
 */
public class BranchNodeSearchTool implements AgentTool {

    private final DataManager dataManager;
    private final NodeSummaryStore summaryStore;

    public BranchNodeSearchTool(DataManager dataManager, NodeSummaryStore summaryStore) {
        this.dataManager = dataManager;
        this.summaryStore = summaryStore;
    }

    @Override
    public String name() {
        return "branch_node_search";
    }

    @Override
    public String description() {
        return "搜索节点概要和消息。参数: query (必填), topK (可选，默认 5)";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String query = call.param("query");
        if (query == null || query.isBlank()) {
            return ToolResult.fail(name(), "缺少必填参数: query");
        }

        int topK = 5;
        try { topK = Integer.parseInt(call.param("topK")); } catch (Exception ignored) {}

        List<Hit> hits = new ArrayList<>();

        // 1. 搜索 NodeSummary
        for (NodeSummary summary : summaryStore.loadAll()) {
            int score = matchScore(query, summary.title() + " " + summary.summary());
            if (score > 0) {
                hits.add(new Hit(summary.nodeId(), summary.title(), "summary",
                        summary.summary(), score));
            }
        }

        // 2. 搜索 DataDocument 正文
        List<DataDocument> branches = dataManager.listBranches();
        for (DataDocument doc : branches) {
            String body = doc.body();
            if (body == null) continue;
            int score = matchScore(query, body);
            if (score > 0) {
                String snippet = extractSnippet(body, query, 120);
                hits.add(new Hit(doc.id(),
                        doc.frontMatter().getOrDefault("name", doc.id()),
                        "body", snippet, score / 2)); // body 匹配权重降低
            }
        }

        // 按分数排序
        hits.sort((a, b) -> Integer.compare(b.score, a.score));

        // 截断到 topK
        List<Hit> topHits = hits.subList(0, Math.min(topK, hits.size()));

        StringBuilder sb = new StringBuilder();
        sb.append("=== 搜索: \"").append(query).append("\" (").append(topHits.size()).append(" 结果) ===\n\n");
        for (Hit hit : topHits) {
            sb.append("## ").append(hit.nodeId).append(" — ").append(hit.title)
                    .append(" (").append(hit.field).append(", score=").append(hit.score).append(")\n");
            String snippet = hit.snippet;
            if (snippet.length() > 200) snippet = snippet.substring(0, 197) + "...";
            sb.append(snippet).append("\n\n");
        }

        return ToolResult.ok(name(), java.util.List.of(new ToolResult.Item("search:" + query, "", sb.toString().trim(), 1.0)));
    }

    private int matchScore(String query, String text) {
        if (text == null) return 0;
        String lowerText = text.toLowerCase();
        String lowerQuery = query.toLowerCase();
        int score = 0;
        for (String term : lowerQuery.split("\\s+")) {
            if (term.length() < 2) continue;
            if (lowerText.contains(term)) score += 10;
        }
        return score;
    }

    private String extractSnippet(String text, String query, int maxLen) {
        String lower = text.toLowerCase();
        int pos = lower.indexOf(query.toLowerCase());
        if (pos < 0) pos = 0;
        int start = Math.max(0, pos - maxLen / 2);
        int end = Math.min(text.length(), pos + maxLen / 2);
        return (start > 0 ? "..." : "") + text.substring(start, end).trim() + (end < text.length() ? "..." : "");
    }

    private record Hit(String nodeId, String title, String field, String snippet, int score) {}
}
