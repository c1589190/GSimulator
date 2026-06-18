package com.gsim.interaction.commands;

import com.gsim.branch.BranchAnalysis;
import com.gsim.branch.BranchAnalyzer;
import com.gsim.branch.BranchChildSummary;
import com.gsim.branch.WorldContentStats;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;

import java.util.List;

/**
 * /branch — 节点态势分析命令。
 *
 * 子命令：
 *   /branch                      等价于 /branch analyze
 *   /branch analyze [branchId] [full]  分析当前或指定节点
 *   /branch children               列出直接子分支
 *   /branch stats                  世界内容统计
 *   /branch old                    老节点判断
 *   /branch next                   可前进分支列表
 *   /branch summary                compact markdown 预览
 */
public class BranchCommand implements InteractionCommand {

    private final BranchAnalyzer analyzer;

    public BranchCommand(BranchAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public String name() {
        return "branch";
    }

    @Override
    public String description() {
        return "分析当前或指定分支节点状况（节点年龄、子分支、消息统计等）";
    }

    @Override
    public String usage() {
        return "/branch [analyze [branchId] [full]|children|stats|old|next|summary]";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        String full = String.join(" ", args).trim();
        String[] parts = full.split("\\s+");
        String sub = (parts.length > 0 && !parts[0].isBlank()) ? parts[0] : "analyze";

        try {
            return switch (sub) {
                case "analyze" -> analyze(parts);
                case "children" -> children(parts);
                case "stats" -> stats(parts);
                case "old" -> old(parts);
                case "next" -> next(parts);
                case "summary" -> summary(parts);
                default -> InteractionResult.fail("未知子命令: " + sub + "\n用法: " + usage());
            };
        } catch (Exception e) {
            return InteractionResult.fail("分析失败: " + e.getMessage());
        }
    }

    // ---- subcommand handlers ----

    private InteractionResult analyze(String[] parts) {
        String branchId = null;
        String detailLevel = "compact";

        // /branch analyze [branchId] [full]
        for (int i = 1; i < parts.length; i++) {
            if ("full".equalsIgnoreCase(parts[i])) {
                detailLevel = "full";
            } else if ("compact".equalsIgnoreCase(parts[i])) {
                detailLevel = "compact";
            } else {
                branchId = parts[i];
            }
        }

        BranchAnalysis analysis = analyzer.analyze(branchId, detailLevel);
        String output = "full".equalsIgnoreCase(detailLevel)
                ? BranchAnalyzer.renderFullMarkdown(analysis)
                : BranchAnalyzer.renderCompactMarkdown(analysis);

        return InteractionResult.ok(output);
    }

    private InteractionResult children(String[] parts) {
        String branchId = parts.length > 1 ? parts[1] : null;
        List<BranchChildSummary> kids = analyzer.listChildren(branchId);

        StringBuilder sb = new StringBuilder();
        sb.append("当前节点可前进分支: ").append(kids.size()).append(" 个\n\n");
        if (kids.isEmpty()) {
            sb.append("无后续分支。可使用 /nextturn <世界时间> <备注> 创建。\n");
        } else {
            for (BranchChildSummary c : kids) {
                sb.append("- ").append(c.branchId()).append(" — \"").append(c.name()).append("\"\n");
                sb.append("  Turn: ").append(c.turn())
                        .append(" | World Time: ").append(c.worldTime())
                        .append(" | Status: ").append(c.status());
                if (c.hasSimulationResult()) sb.append(" | 已推演");
                sb.append(" | Messages: ").append(c.messageCount()).append("\n");
            }
        }
        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult stats(String[] parts) {
        String branchId = parts.length > 1 ? parts[1] : null;
        BranchAnalysis analysis = analyzer.analyze(branchId, "compact");
        WorldContentStats wcs = analyzer.analyzeWorldContent();

        StringBuilder sb = new StringBuilder();
        sb.append("=== 节点统计 ===\n\n");

        sb.append("【基本信息】\n");
        sb.append("  World: ").append(analysis.activeWorld()).append("\n");
        sb.append("  Branch: ").append(analysis.activeBranchId())
                .append(" — \"").append(analysis.activeBranchName()).append("\"\n");
        sb.append("  Status: ").append(analysis.status())
                .append(" | Age: ").append(analysis.nodeAgeStatus()).append("\n");
        sb.append("  Old Node: ").append(analysis.oldNode() ? "是" : "否").append("\n");

        sb.append("\n【内容】\n");
        sb.append("  实体: ").append(wcs.entityCount());
        if (!wcs.entityNames().isEmpty())
            sb.append(" (").append(String.join(", ", wcs.entityNames())).append(")");
        sb.append("\n");
        sb.append("  玩家: ").append(wcs.playerCount());
        if (!wcs.playerNames().isEmpty())
            sb.append(" (").append(String.join(", ", wcs.playerNames())).append(")");
        sb.append("\n");
        sb.append("  规则: ").append(wcs.ruleSectionCount()).append(" 节\n");
        sb.append("  世界观: ").append(wcs.worldSectionCount()).append(" 节\n");

        sb.append("\n【消息】\n");
        sb.append("  Total: ").append(analysis.messageCount()).append("\n");
        sb.append("  Chat: user=").append(analysis.chatUserCount())
                .append(" response=").append(analysis.chatResponseCount()).append("\n");
        sb.append("  Sim: user=").append(analysis.simUserCount())
                .append(" response=").append(analysis.simResponseCount()).append("\n");
        sb.append("  Tool: call=").append(analysis.toolCallCount())
                .append(" result=").append(analysis.toolResultCount()).append("\n");

        sb.append("\n【分支结构】\n");
        sb.append("  子分支: ").append(analysis.childBranchCount()).append("\n");
        sb.append("  兄弟分支: ").append(analysis.siblingBranchCount()).append("\n");
        sb.append("  父链深度: ").append(analysis.parentChainLength()).append("\n");

        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult old(String[] parts) {
        String branchId = parts.length > 1 ? parts[1] : null;
        BranchAnalysis analysis = analyzer.analyze(branchId, "compact");

        StringBuilder sb = new StringBuilder();
        sb.append("节点: ").append(analysis.activeBranchId())
                .append(" — \"").append(analysis.activeBranchName()).append("\"\n");
        sb.append("Status: ").append(analysis.status())
                .append(" | Age: ").append(analysis.nodeAgeStatus()).append("\n");
        sb.append("Old Node: ").append(analysis.oldNode() ? "是 ⚠️" : "否").append("\n");

        if (analysis.oldNode()) {
            sb.append("\n判定原因:\n");
            if (analysis.resolved()) sb.append("  - status=resolved\n");
            if (analysis.hasSimulationResult()) sb.append("  - 有推演结果（三、推演结果非空）\n");
            if (analysis.simResponseCount() > 0) sb.append("  - 有 sim_response 消息\n");
            if (analysis.childBranchCount() > 0) sb.append("  - 有 ").append(analysis.childBranchCount()).append(" 个子分支\n");
            if (analysis.chatUserCount() >= 2 && analysis.chatResponseCount() >= 2)
                sb.append("  - 多轮对话历史（≥2 对 chat 消息）\n");
            sb.append("\n建议: ").append(analysis.nextActionHint());
        }

        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult next(String[] parts) {
        String branchId = parts.length > 1 ? parts[1] : null;
        List<BranchChildSummary> kids = analyzer.listChildren(branchId);

        StringBuilder sb = new StringBuilder();
        if (kids.isEmpty()) {
            sb.append("当前节点暂无后续分支。\n");
            sb.append("使用 /nextturn <世界时间> <备注> 创建子节点。\n");
        } else {
            sb.append("可前进分支 (").append(kids.size()).append(" 个):\n\n");
            for (BranchChildSummary c : kids) {
                sb.append("  - ").append(c.branchId()).append(" — \"").append(c.name()).append("\"\n");
                sb.append("    Turn: ").append(c.turn())
                        .append(" | ").append(c.worldTime())
                        .append(" | ").append(c.status());
                if (c.hasSimulationResult()) sb.append(" | 已推演");
                sb.append("\n");
                sb.append("    切换: /node switch ").append(c.branchId()).append("\n");
            }
        }
        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult summary(String[] parts) {
        String branchId = parts.length > 1 ? parts[1] : null;
        BranchAnalysis analysis = analyzer.analyze(branchId, "compact");
        return InteractionResult.ok(BranchAnalyzer.renderCompactMarkdown(analysis));
    }
}
