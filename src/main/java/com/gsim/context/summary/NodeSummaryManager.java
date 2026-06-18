package com.gsim.context.summary;

import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * NodeSummary 管理器 — 节点摘要的生成和更新。
 *
 * <p>第一版使用 rule-based summarizer，不调用 LLM。
 * 预留 LlmNodeSummarySummarizer 接口。
 */
public class NodeSummaryManager {

    private static final Logger log = LoggerFactory.getLogger(NodeSummaryManager.class);

    private final NodeSummaryStore store;
    private final DataManager dataManager;
    private final BranchMessageStore messageStore;
    private final NodeSummarySummarizer summarizer;

    public NodeSummaryManager(NodeSummaryStore store, DataManager dataManager,
                               BranchMessageStore messageStore) {
        this(store, dataManager, messageStore, new RuleBasedNodeSummarySummarizer());
    }

    public NodeSummaryManager(NodeSummaryStore store, DataManager dataManager,
                               BranchMessageStore messageStore,
                               NodeSummarySummarizer summarizer) {
        this.store = store;
        this.dataManager = dataManager;
        this.messageStore = messageStore;
        this.summarizer = summarizer;
    }

    /**
     * 确保节点有摘要，没有则 fallback 生成。
     */
    public NodeSummary ensureSummary(String branchId) {
        Optional<NodeSummary> existing = store.findByNodeId(branchId);
        if (existing.isPresent()) return existing.get();

        DataDocument doc = dataManager.readById(branchId);
        if (doc == null) {
            NodeSummary empty = new NodeSummary(
                    branchId, branchId, branchId, "", List.of(),
                    List.of(), null, Instant.now(), Instant.now()
            );
            store.save(empty);
            return empty;
        }

        List<BranchMessage> messages;
        try {
            messages = messageStore.listMessages(branchId);
        } catch (java.io.IOException e) {
            log.warn("Failed to read messages for {}: {}", branchId, e.getMessage());
            messages = List.of();
        }
        NodeSummary generated = summarizer.summarize(doc, messages);
        store.save(generated);
        log.info("Generated NodeSummary for {}", branchId);
        return generated;
    }

    /**
     * 从 simulation 结果更新摘要。
     */
    public NodeSummary updateFromSimulation(String branchId, String finalText, List<?> trace) {
        DataDocument doc = dataManager.readById(branchId);
        if (doc == null) {
            return ensureSummary(branchId);
        }

        List<BranchMessage> messages;
        try {
            messages = messageStore.listMessages(branchId);
        } catch (java.io.IOException e) {
            log.warn("Failed to read messages for {}: {}", branchId, e.getMessage());
            messages = List.of();
        }
        NodeSummary updated = summarizer.summarizeFromSimulation(doc, messages, finalText, trace);
        store.save(updated);
        log.info("Updated NodeSummary from simulation for {}", branchId);
        return updated;
    }

    /**
     * 获取节点摘要。
     */
    public Optional<NodeSummary> getSummary(String branchId) {
        return store.findByNodeId(branchId);
    }

    /**
     * NodeSummary 生成器接口。
     */
    public interface NodeSummarySummarizer {
        NodeSummary summarize(DataDocument doc, List<BranchMessage> messages);
        NodeSummary summarizeFromSimulation(DataDocument doc, List<BranchMessage> messages,
                                             String finalText, List<?> trace);
    }

    /**
     * Rule-based 实现 — 不调用 LLM。
     */
    public static class RuleBasedNodeSummarySummarizer implements NodeSummarySummarizer {

        @Override
        public NodeSummary summarize(DataDocument doc, List<BranchMessage> messages) {
            String nodeId = doc.id();
            String title = doc.frontMatter().getOrDefault("name", nodeId);
            String summary = buildSummary(doc, messages);
            List<String> tags = inferTags(doc, messages);

            return new NodeSummary(
                    nodeId, nodeId, title, summary, tags,
                    messages.stream().map(BranchMessage::id).toList(),
                    null, Instant.now(), Instant.now()
            );
        }

        @Override
        public NodeSummary summarizeFromSimulation(DataDocument doc, List<BranchMessage> messages,
                                                    String finalText, List<?> trace) {
            String nodeId = doc.id();
            String title = doc.frontMatter().getOrDefault("name", nodeId);
            String summary = buildSummaryFromResult(doc, finalText);
            List<String> tags = inferTags(doc, messages);

            // 添加 sim 相关标签
            if (finalText != null && !finalText.isEmpty()) {
                tags.add("simulated");
            }

            List<String> msgIds = new java.util.ArrayList<>(
                    messages.stream().map(BranchMessage::id).toList());
            if (trace != null && !trace.isEmpty()) {
                msgIds.add("trace-" + trace.size());
            }

            return new NodeSummary(
                    nodeId, nodeId, title, summary, tags,
                    msgIds, doc.id() + "-output", Instant.now(), Instant.now()
            );
        }

        private String buildSummary(DataDocument doc, List<BranchMessage> messages) {
            StringBuilder sb = new StringBuilder();

            // 1. 输入摘要
            String input = extractSection(doc.body(), "一、本节点输入");
            if (input != null && !input.isBlank()) {
                String cleaned = input.trim().replaceAll("\\s+", " ");
                if (cleaned.length() > 200) cleaned = cleaned.substring(0, 197) + "...";
                sb.append("输入：").append(cleaned);
            }

            // 2. 消息数量概览
            long userMsgs = messages.stream().filter(m -> "user".equals(m.role())).count();
            long assistantMsgs = messages.stream().filter(m -> "assistant".equals(m.role())).count();
            if (userMsgs > 0 || assistantMsgs > 0) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append("（用户消息 ").append(userMsgs)
                        .append("，助手消息 ").append(assistantMsgs).append("）");
            }

            // 3. 推演结果首段
            String simResult = extractSection(doc.body(), "三、推演结果");
            if (simResult != null && !simResult.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n");
                String firstPara = extractFirstParagraph(simResult);
                if (firstPara.length() > 250) firstPara = firstPara.substring(0, 247) + "...";
                if (!firstPara.isBlank()) sb.append(firstPara);
            }

            return sb.toString().trim();
        }

        private String buildSummaryFromResult(DataDocument doc, String finalText) {
            if (finalText != null && !finalText.isBlank()) {
                String cleaned = finalText.trim().replaceAll("\\s+", " ");
                if (cleaned.length() > 300) cleaned = cleaned.substring(0, 297) + "...";
                return cleaned;
            }
            return buildSummary(doc, List.of());
        }

        private List<String> inferTags(DataDocument doc, List<BranchMessage> messages) {
            java.util.Set<String> tags = new java.util.LinkedHashSet<>();
            String body = doc.body();

            if (body != null) {
                if (body.contains("三、推演结果") && !extractSection(body, "三、推演结果").isBlank())
                    tags.add("simulated");
                if (body.contains("工具调用") || body.contains("tool_call"))
                    tags.add("tool-used");
            }

            long chatMsgs = messages.stream().filter(m -> "user".equals(m.role())).count();
            if (chatMsgs >= 3) tags.add("discussion");

            String turn = doc.frontMatter().getOrDefault("turn", "");
            if (!turn.isEmpty() && !"0".equals(turn)) tags.add("turn-" + turn);

            return tags.stream().toList();
        }

        private String extractSection(String body, String heading) {
            if (body == null) return "";
            String marker = "## " + heading;
            int start = body.indexOf(marker);
            if (start < 0) return "";
            start += marker.length();
            int end = body.indexOf("\n## ", start);
            if (end < 0) end = body.length();
            return body.substring(start, end).trim();
        }

        private String extractFirstParagraph(String text) {
            if (text == null) return "";
            String[] paras = text.split("\n\n");
            for (String para : paras) {
                String trimmed = para.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")
                        && !trimmed.startsWith("-") && !trimmed.startsWith("*")) {
                    return trimmed;
                }
            }
            // 回退到第一个非空行
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    return trimmed;
                }
            }
            return text.trim();
        }
    }
}
