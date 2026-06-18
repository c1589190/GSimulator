package com.gsim.context.summary;

import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 分支概要链渲染器 — 沿 parent 链渲染节点摘要。
 *
 * <p>不渲染完整 raw messages，只渲染 NodeSummary 概要。
 * 预算控制：最近 5 个详细，更早的一行一个，超预算合并。
 */
public class BranchPathSummaryRenderer {

    private static final Logger log = LoggerFactory.getLogger(BranchPathSummaryRenderer.class);

    public static final int DEFAULT_RECENT_COUNT = 5;
    public static final int MAX_TOTAL_NODES = 30;

    private final DataManager dataManager;
    private final NodeSummaryStore summaryStore;

    public BranchPathSummaryRenderer(DataManager dataManager, NodeSummaryStore summaryStore) {
        this.dataManager = dataManager;
        this.summaryStore = summaryStore;
    }

    /**
     * 渲染当前活动分支的概要链。
     */
    public String renderActivePath() {
        return renderPath(dataManager.getActiveBranch(), new BranchPathRenderOptions());
    }

    /**
     * 渲染指定分支的概要链。
     */
    public String renderPath(String branchId, BranchPathRenderOptions options) {
        List<DataDocument> chain = dataManager.getBranchChain(branchId);
        if (chain == null) chain = List.of();

        // 反转为从 root 到 leaf 的时间顺序
        List<DataDocument> reversed = new ArrayList<>(chain);
        Collections.reverse(reversed);

        int total = reversed.size();
        int recentCount = options.recentCount > 0 ? options.recentCount : DEFAULT_RECENT_COUNT;

        StringBuilder sb = new StringBuilder();
        sb.append("## Branch Evolution Summary\n\n");

        // 早期节点合并
        int mergeThreshold = total - recentCount;
        if (total > MAX_TOTAL_NODES || (mergeThreshold > recentCount * 2)) {
            // 合并早期历史
            int mergedCount = Math.max(0, total - recentCount);
            if (mergedCount > 0) {
                sb.append("### 早期历史（").append(mergedCount).append(" 个节点）\n\n");
                List<DataDocument> earlyNodes = reversed.subList(0, mergedCount);
                for (DataDocument doc : earlyNodes) {
                    NodeSummary summary = getOrFallbackSummary(doc);
                    String title = summary.title().isEmpty() ? extractName(doc) : summary.title();
                    String oneLine = summary.summary();
                    if (oneLine.length() > 120) oneLine = oneLine.substring(0, 117) + "...";
                    sb.append("- **").append(title).append("**：").append(oneLine).append("\n");
                }
                sb.append("\n");
            }

            // 详细显示近期节点
            sb.append("### 近期节点\n\n");
            List<DataDocument> recentNodes = reversed.subList(Math.max(0, total - recentCount), total);
            for (int i = 0; i < recentNodes.size(); i++) {
                renderDetailedNode(sb, recentNodes.get(i), i + 1 + Math.max(0, total - recentCount));
            }
        } else {
            // 节点数量较少，全部详细显示
            for (int i = 0; i < reversed.size(); i++) {
                renderDetailedNode(sb, reversed.get(i), i + 1);
            }
        }

        return sb.toString().trim();
    }

    private void renderDetailedNode(StringBuilder sb, DataDocument doc, int index) {
        NodeSummary summary = getOrFallbackSummary(doc);
        String nodeId = doc.id();
        String title = summary.title().isEmpty() ? extractName(doc) : summary.title();

        sb.append("### ").append(nodeId).append("：").append(title).append("\n");

        String text = summary.summary();
        if (!text.isEmpty()) {
            sb.append(text).append("\n\n");
        } else {
            sb.append("_（暂无摘要）_\n\n");
        }
    }

    /**
     * 获取 NodeSummary，无则 fallback 生成。
     */
    public NodeSummary getOrFallbackSummary(DataDocument doc) {
        String nodeId = doc.id();
        Optional<NodeSummary> existing = summaryStore.findByNodeId(nodeId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Fallback 生成
        return fallbackSummary(doc);
    }

    /**
     * Fallback：从 branch title / sim result / input 生成短摘要。
     */
    private NodeSummary fallbackSummary(DataDocument doc) {
        String nodeId = doc.id();
        String title = extractName(doc);
        String summary = generateFallbackSummary(doc);

        return new NodeSummary(
                nodeId, nodeId, title, summary, List.of(),
                List.of(), null, java.time.Instant.now(), java.time.Instant.now()
        );
    }

    private String generateFallbackSummary(DataDocument doc) {
        String body = doc.body();

        // 1. 尝试提取推演结果首段
        String simResult = extractSection(body, "三、推演结果");
        if (simResult != null && !simResult.isBlank()) {
            String[] lines = simResult.trim().split("\n");
            StringBuilder firstPara = new StringBuilder();
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                firstPara.append(trimmed).append(" ");
                if (firstPara.length() > 200) break;
            }
            if (firstPara.length() > 20) {
                String result = firstPara.toString().trim();
                if (result.length() > 200) result = result.substring(0, 197) + "...";
                return result;
            }
        }

        // 2. 尝试提取本节点输入
        String input = extractSection(body, "一、本节点输入");
        if (input != null && !input.isBlank()) {
            String cleaned = input.trim().replaceAll("\\s+", " ");
            if (cleaned.length() > 150) cleaned = cleaned.substring(0, 147) + "...";
            if (!cleaned.isBlank()) return cleaned;
        }

        // 3. 空节点
        return "";
    }

    private String extractName(DataDocument doc) {
        return doc.frontMatter().getOrDefault("name", doc.id());
    }

    private String extractSection(String body, String heading) {
        if (body == null) return null;
        String marker = "## " + heading;
        int start = body.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        // 找下一个 ## 标题
        int end = body.indexOf("\n## ", start);
        if (end < 0) end = body.length();
        return body.substring(start, end).trim();
    }

    /**
     * 渲染选项。
     */
    public record BranchPathRenderOptions(
            int recentCount,
            boolean includeNodeIds
    ) {
        public BranchPathRenderOptions() {
            this(DEFAULT_RECENT_COUNT, true);
        }
    }
}
