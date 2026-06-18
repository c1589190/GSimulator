package com.gsim.branch;

import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.player.PlayerProfile;
import com.gsim.player.PlayerProfileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * BranchAnalyzer — 节点态势分析引擎。
 *
 * 分析当前 active branch 的结构状态：节点年龄、消息统计、
 * 实体/玩家/规则概要、子分支列表、下一步建议。
 *
 * 只读，不修改任何文件，不创建分支，不推进时间。
 */
public class BranchAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(BranchAnalyzer.class);

    private final DataManager dm;
    private final BranchMessageStore messageStore;
    private final PlayerProfileManager profileManager;

    public BranchAnalyzer(DataManager dm, BranchMessageStore messageStore,
                          PlayerProfileManager profileManager) {
        this.dm = dm;
        this.messageStore = messageStore;
        this.profileManager = profileManager;
    }

    /** 分析当前 active branch（full 模式）。 */
    public BranchAnalysis analyze() {
        return analyze(null);
    }

    /** 分析指定 branch。branchId 为 null 则用 active。 */
    public BranchAnalysis analyze(String branchId) {
        return analyze(branchId, "full");
    }

    /** 分析指定 branch，支持 compact/full 细节级别。 */
    public BranchAnalysis analyze(String branchId, String detailLevel) {
        String bid = (branchId == null || branchId.isBlank())
                ? dm.getActiveBranch() : DataManager.normalizeBranchId(branchId);

        DataDocument doc = dm.readById(bid);
        if (doc == null) {
            return empty(bid);
        }

        Map<String, String> fm = doc.frontMatter();
        String world = dm.getActiveWorld();
        String name = doc.name();
        String parent = fm.getOrDefault("parent", "none");
        int turn = parseInt(fm.get("turn"), 0);
        String worldTime = fm.getOrDefault("world_time", "?");
        String status = fm.getOrDefault("status", "active");

        // 消息统计
        List<BranchMessage> messages = readMessages(bid);
        int msgCount = messages.size();
        int chatUser = 0, chatResp = 0, simUser = 0, simResp = 0, toolCall = 0, toolResult = 0;
        for (BranchMessage m : messages) {
            switch (m.type()) {
                case "chat_user" -> chatUser++;
                case "chat_response" -> chatResp++;
                case "sim_user" -> simUser++;
                case "sim_response" -> simResp++;
                case "tool_call" -> toolCall++;
                case "tool_result" -> toolResult++;
            }
        }

        // 推演结果检查
        boolean hasSimResult = hasSimulationResult(bid);
        boolean resolved = "resolved".equals(status);

        // 子/兄弟分支
        List<DataDocument> children = dm.getChildBranches(bid);
        int childCount = children.size();
        List<BranchChildSummary> childSummaries = buildChildSummaries(children);
        int sibCount = dm.getSiblingBranches(bid).size();

        // 父链
        List<DataDocument> chain = dm.getBranchChain(bid);
        int chainLen = chain.size();

        // 输入
        String inputBody = dm.getInputBody();
        int inputLines = (inputBody == null || inputBody.isBlank()) ? 0
                : (int) inputBody.lines().filter(l -> !l.isBlank()).count();
        boolean hasInput = inputLines > 0;

        // 世界内容
        WorldContentStats contentStats = analyzeWorldContent();

        // 风险摘要
        String risks = extractRiskSummary(doc);

        // 分类
        NodeAgeStatus ageStatus = classifyNode(doc, messages, childCount > 0);
        boolean old = isOldNode(doc, messages, childCount > 0, hasSimResult);

        // 下一步建议
        String hint = buildNextActionHint(ageStatus, old, childCount, contentStats);

        return new BranchAnalysis(
                world, bid, name, parent, turn, worldTime, status,
                ageStatus, old, resolved, hasSimResult, hasInput, inputLines,
                msgCount, chatUser, chatResp, simUser, simResp, toolCall, toolResult,
                childCount, childSummaries, sibCount, chainLen,
                contentStats.entityCount(), contentStats.playerCount(),
                contentStats.ruleSectionCount(), contentStats.worldSectionCount(),
                contentStats.entityNames(), contentStats.playerNames(),
                risks, hint);
    }

    /** 分析世界内容统计。 */
    public WorldContentStats analyzeWorldContent() {
        String entitiesBody = readBaseBody("entities.base");
        String worldBody = readBaseBody("world.base");
        String rulesBody = readBaseBody("rules.base");

        List<PlayerProfile> players = profileManager.listPlayers();
        WorldContentStats stats = WorldContentStats.from(entitiesBody, players);

        int ruleSecs = countH2Sections(rulesBody);
        int worldSecs = countH2Sections(worldBody);

        return stats.withSectionCounts(ruleSecs, worldSecs);
    }

    /** 列出直接子分支摘要。 */
    public List<BranchChildSummary> listChildren(String branchId) {
        String bid = (branchId == null || branchId.isBlank())
                ? dm.getActiveBranch() : DataManager.normalizeBranchId(branchId);
        return buildChildSummaries(dm.getChildBranches(bid));
    }

    /** 检查指定分支是否有非 trivial 推演结果。 */
    public boolean hasSimulationResult(String branchId) {
        return dm.hasSimulationResult(branchId);
    }

    /** 判断指定分支是否为老节点。 */
    public boolean isOldNode(String branchId) {
        String bid = (branchId == null || branchId.isBlank())
                ? dm.getActiveBranch() : DataManager.normalizeBranchId(branchId);
        DataDocument doc = dm.readById(bid);
        if (doc == null) return false;
        List<BranchMessage> msgs = readMessages(bid);
        boolean hasChildren = !dm.getChildBranches(bid).isEmpty();
        boolean hasSim = dm.hasSimulationResult(bid);
        return isOldNode(doc, msgs, hasChildren, hasSim);
    }

    // ---- 静态渲染方法 ----

    /** 渲染为 compact markdown（注入 LLM 上下文）。 */
    public static String renderCompactMarkdown(BranchAnalysis a) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前节点态势摘要\n\n");

        sb.append("- **节点**: ").append(a.activeBranchId())
                .append(" — \"").append(a.activeBranchName()).append("\"\n");
        sb.append("- **状态**: ").append(a.status())
                .append(" | ").append(a.nodeAgeStatus()).append("\n");

        if (a.oldNode()) {
            sb.append("- **⚠️ 旧节点**: 是");
            String reason = oldNodeReason(a);
            if (!reason.isBlank()) sb.append(" — ").append(reason);
            sb.append("\n");
        } else {
            sb.append("- **旧节点**: 否\n");
        }

        sb.append("- **世界时间**: ").append(a.worldTime())
                .append(" | **Turn**: ").append(a.turn()).append("\n");
        sb.append("- **父节点**: ").append(a.parentBranchId())
                .append(" | **父链深度**: ").append(a.parentChainLength()).append("\n");

        sb.append("\n### 消息统计\n");
        sb.append("- 总消息: ").append(a.messageCount())
                .append(" (chat: ").append(a.chatUserCount() + a.chatResponseCount())
                .append(" / sim: ").append(a.simUserCount() + a.simResponseCount())
                .append(" / tool: ").append(a.toolCallCount() + a.toolResultCount()).append(")\n");

        sb.append("\n### 内容概况\n");
        sb.append("- 实体: ").append(a.entityCount())
                .append(" | 玩家: ").append(a.playerCount())
                .append(" | 规则: ").append(a.ruleSectionCount()).append(" 节")
                .append(" | 世界观: ").append(a.worldSectionCount()).append(" 节\n");
        sb.append("- Input.md: ").append(a.inputLineCount()).append(" 行")
                .append(a.hasInput() ? "" : " (无待结算内容)").append("\n");

        sb.append("\n### 可前进分支\n");
        if (a.children().isEmpty()) {
            sb.append("- 无（当前节点暂无后续分支）\n");
        } else {
            for (BranchChildSummary c : a.children()) {
                sb.append("- ").append(c.branchId()).append(" — \"").append(c.name()).append("\"");
                sb.append(" [turn=").append(c.turn());
                if (c.hasSimulationResult()) sb.append(", 已推演");
                sb.append("]\n");
            }
        }

        sb.append("\n### 建议行动\n");
        sb.append(a.nextActionHint()).append("\n");

        return sb.toString();
    }

    /** 渲染为 full markdown（CLI 显示）。 */
    public static String renderFullMarkdown(BranchAnalysis a) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 节点态势分析\n\n");

        sb.append("## 基本信息\n");
        sb.append("- **World**: ").append(a.activeWorld()).append("\n");
        sb.append("- **Branch**: ").append(a.activeBranchId())
                .append(" — \"").append(a.activeBranchName()).append("\"\n");
        sb.append("- **Parent**: ").append(a.parentBranchId()).append("\n");
        sb.append("- **Turn**: ").append(a.turn()).append("\n");
        sb.append("- **World Time**: ").append(a.worldTime()).append("\n");
        sb.append("- **Status**: ").append(a.status()).append("\n");

        sb.append("\n## 节点状态\n");
        sb.append("- **Age Status**: ").append(a.nodeAgeStatus()).append("\n");
        sb.append("- **Old Node**: ").append(a.oldNode() ? "是" : "否");
        if (a.oldNode()) sb.append(" — ").append(oldNodeReason(a));
        sb.append("\n");
        sb.append("- **Resolved**: ").append(a.resolved() ? "是" : "否").append("\n");
        sb.append("- **Has Simulation**: ").append(a.hasSimulationResult() ? "是" : "否").append("\n");
        sb.append("- **Has Input**: ").append(a.hasInput() ? "是" : "否")
                .append(" (").append(a.inputLineCount()).append(" 行)\n");

        sb.append("\n## 消息统计\n");
        sb.append("- **Total**: ").append(a.messageCount()).append("\n");
        sb.append("- Chat: user=").append(a.chatUserCount())
                .append(" response=").append(a.chatResponseCount()).append("\n");
        sb.append("- Sim: user=").append(a.simUserCount())
                .append(" response=").append(a.simResponseCount()).append("\n");
        sb.append("- Tool: call=").append(a.toolCallCount())
                .append(" result=").append(a.toolResultCount()).append("\n");

        sb.append("\n## 内容概况\n");
        sb.append("- **实体**: ").append(a.entityCount());
        if (!a.entityNames().isEmpty())
            sb.append(" (").append(String.join(", ", a.entityNames())).append(")");
        sb.append("\n");
        sb.append("- **玩家**: ").append(a.playerCount());
        if (!a.playerNames().isEmpty())
            sb.append(" (").append(String.join(", ", a.playerNames())).append(")");
        sb.append("\n");
        sb.append("- **规则**: ").append(a.ruleSectionCount()).append(" 节\n");
        sb.append("- **世界观**: ").append(a.worldSectionCount()).append(" 节\n");
        sb.append("- **父链深度**: ").append(a.parentChainLength()).append("\n");

        sb.append("\n## 分支结构\n");
        sb.append("- **子分支**: ").append(a.childBranchCount()).append("\n");
        for (BranchChildSummary c : a.children()) {
            sb.append("  - ").append(c.branchId()).append(" — \"").append(c.name()).append("\"");
            sb.append(" [turn=").append(c.turn())
                    .append(", status=").append(c.status());
            if (c.hasSimulationResult()) sb.append(", 已推演");
            sb.append(", msgs=").append(c.messageCount()).append("]\n");
        }
        sb.append("- **兄弟分支**: ").append(a.siblingBranchCount()).append("\n");

        if (!a.riskSummary().isBlank()) {
            sb.append("\n## 风险摘要\n");
            sb.append(a.riskSummary()).append("\n");
        }

        sb.append("\n## 建议行动\n");
        sb.append(a.nextActionHint()).append("\n");

        return sb.toString();
    }

    // ---- private helpers ----

    private BranchAnalysis empty(String bid) {
        WorldContentStats emptyStats = WorldContentStats.empty();
        return new BranchAnalysis(
                dm.getActiveWorld(), bid, "(not found)", "none",
                0, "?", "active",
                NodeAgeStatus.UNKNOWN, false, false, false, false, 0,
                0, 0, 0, 0, 0, 0, 0,
                0, List.of(), 0, 0,
                emptyStats.entityCount(), emptyStats.playerCount(),
                emptyStats.ruleSectionCount(), emptyStats.worldSectionCount(),
                List.of(), List.of(),
                "", "无法分析：分支不存在。");
    }

    private List<BranchMessage> readMessages(String branchId) {
        try {
            return messageStore.listMessages(branchId);
        } catch (IOException e) {
            log.warn("Failed to read messages for {}: {}", branchId, e.getMessage());
            return List.of();
        }
    }

    /** 分类节点年龄。 */
    private NodeAgeStatus classifyNode(DataDocument doc, List<BranchMessage> messages, boolean hasChildren) {
        if (hasChildren) return NodeAgeStatus.BRANCHED_OLD_NODE;

        boolean hasSimResult = dm.hasSimulationResult(doc.id());
        boolean hasSimResp = messages.stream()
                .anyMatch(m -> "sim_response".equals(m.type()));
        if (hasSimResult || hasSimResp) return NodeAgeStatus.SIMULATED_NODE;

        long chatMsgs = messages.stream()
                .filter(m -> "chat_user".equals(m.type()) || "chat_response".equals(m.type()))
                .count();
        if (chatMsgs >= 4) return NodeAgeStatus.DISCUSSION_NODE;

        boolean hasInput = hasNonTrivialInput(doc);
        if (hasInput) return NodeAgeStatus.NEW_WITH_INPUT;

        boolean empty = messages.isEmpty() && !hasSimResult && !hasInput;
        if (empty) return NodeAgeStatus.NEW_EMPTY;

        return NodeAgeStatus.UNKNOWN;
    }

    /** 老节点判定。
     *
     * 满足任意条件即为老节点：
     * 1. 有 sim_response 消息
     * 2. status=resolved 且节点有实质内容（有消息或有推演结果）
     * 3. 有子分支
     * 4. 有多轮 chat_user/chat_response 历史 (>= 2 对)
     * 5. 推演结果章节不是"无。"且非空
     *
     * 注意：单纯的 status=resolved 但节点完全为空（模板默认值）不算老节点。
     */
    private boolean isOldNode(DataDocument doc, List<BranchMessage> messages,
                               boolean hasChildren, boolean hasSimResult) {
        boolean hasMessages = !messages.isEmpty();

        // 1. 有 sim_response
        if (messages.stream().anyMatch(m -> "sim_response".equals(m.type()))) return true;

        // 2. status=resolved 且有实质内容（有消息或有推演结果）
        boolean resolved = "resolved".equals(doc.frontMatter().getOrDefault("status", ""));
        if (resolved && (hasMessages || hasSimResult || hasChildren)) return true;

        // 3. 有子分支
        if (hasChildren) return true;

        // 4. 有推演结果
        if (hasSimResult) return true;

        // 5. 多轮 chat 历史 (>= 2 对)
        long chatUser = messages.stream().filter(m -> "chat_user".equals(m.type())).count();
        long chatResp = messages.stream().filter(m -> "chat_response".equals(m.type())).count();
        if (chatUser >= 2 && chatResp >= 2) return true;

        return false;
    }

    private boolean hasNonTrivialInput(DataDocument doc) {
        String body = doc.body();
        if (body == null) return false;
        // 检查 "一、本节点输入" 章节
        int start = body.indexOf("## 一、本节点输入");
        if (start < 0) return false;
        int end = body.indexOf("\n## ", start + 10);
        if (end < 0) end = body.length();
        String section = body.substring(start, end);
        // 去除标题行和空白后检查内容
        String content = section.replace("## 一、本节点输入", "").trim();
        // 也去除可能的 \n 和 HTML 注释
        content = content.replaceAll("<!--.*?-->", "").trim();
        return !content.isBlank() && !"无。".equals(content) && !"暂无待结算内容。".equals(content);
    }

    private List<BranchChildSummary> buildChildSummaries(List<DataDocument> children) {
        if (children == null || children.isEmpty()) return List.of();
        List<BranchChildSummary> result = new ArrayList<>();
        for (DataDocument c : children) {
            Map<String, String> fm = c.frontMatter();
            int msgCount;
            try {
                msgCount = messageStore.listMessages(c.id()).size();
            } catch (IOException e) {
                msgCount = -1;
            }
            result.add(new BranchChildSummary(
                    c.id(),
                    c.name(),
                    parseInt(fm.get("turn"), 0),
                    fm.getOrDefault("world_time", "?"),
                    fm.getOrDefault("status", "active"),
                    dm.hasSimulationResult(c.id()),
                    msgCount,
                    fm.getOrDefault("updated", "?")));
        }
        return Collections.unmodifiableList(result);
    }

    private String buildNextActionHint(NodeAgeStatus status, boolean oldNode,
                                        int childCount, WorldContentStats stats) {
        if (oldNode && childCount > 0) {
            return "当前节点已有推演结果和后续分支。建议使用 /branch next 查看可前进分支，或 /node switch <id> 切换到已有子节点。如需重推当前节点，使用 /sim。";
        }
        if (oldNode) {
            return "当前节点已完成推演。建议使用 /nextturn <世界时间> <备注> 创建新节点继续推进。";
        }
        if (status == NodeAgeStatus.NEW_EMPTY) {
            StringBuilder sb = new StringBuilder("当前节点为新节点。");
            if (stats.playerCount() == 0) sb.append(" 建议先用 /players add <玩家名> 创建玩家档案。");
            sb.append(" 提交玩家行动后使用 /sim 进行推演结算。");
            return sb.toString();
        }
        if (status == NodeAgeStatus.NEW_WITH_INPUT) {
            return "当前节点有待结算的玩家输入。使用 /sim 进行推演结算。";
        }
        if (status == NodeAgeStatus.DISCUSSION_NODE) {
            return "当前节点有多轮讨论但无推演结果。如需正式推演，使用 /sim。";
        }
        return "可继续在当前节点操作。";
    }

    private String extractRiskSummary(DataDocument doc) {
        String body = doc.body();
        if (body == null) return "";
        int start = body.indexOf("## 九、下节点风险");
        if (start < 0) return "";
        int end = body.indexOf("\n## ", start + 10);
        if (end < 0) end = body.length();
        String section = body.substring(start, end);
        String content = section.replace("## 九、下节点风险", "").trim();
        return (content.isBlank() || "无。".equals(content)) ? "" : content;
    }

    private static String oldNodeReason(BranchAnalysis a) {
        List<String> reasons = new ArrayList<>();
        if (a.resolved()) reasons.add("status=resolved");
        if (a.hasSimulationResult()) reasons.add("有推演结果");
        if (a.simResponseCount() > 0) reasons.add("有 sim_response 消息");
        if (a.childBranchCount() > 0) reasons.add("有 " + a.childBranchCount() + " 个子分支");
        if (a.chatUserCount() >= 2 && a.chatResponseCount() >= 2) reasons.add("多轮对话历史");
        return String.join(", ", reasons);
    }

    private int countH2Sections(String body) {
        if (body == null || body.isBlank()) return 0;
        int count = 0;
        for (String line : body.split("\n")) {
            if (line.trim().startsWith("## ") && !line.trim().startsWith("### ")) {
                count++;
            }
        }
        return count;
    }

    private String readBaseBody(String baseId) {
        DataDocument doc = dm.readById(baseId);
        return doc != null ? doc.body() : "";
    }

    private static int parseInt(String s, int defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
