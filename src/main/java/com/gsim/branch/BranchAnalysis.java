package com.gsim.branch;

import java.util.List;

/**
 * 分支节点态势分析 — 当前或指定 branch 的完整状态快照。
 *
 * 只读，不修改任何文件。
 */
public record BranchAnalysis(
        /** 当前世界名。 */
        String activeWorld,

        /** 分析目标分支 ID。 */
        String activeBranchId,

        /** 分支名。 */
        String activeBranchName,

        /** 父分支 ID。 */
        String parentBranchId,

        /** 回合号。 */
        int turn,

        /** 世界时间。 */
        String worldTime,

        /** front matter status。 */
        String status,

        /** 节点年龄分类。 */
        NodeAgeStatus nodeAgeStatus,

        /** 是否为老节点。 */
        boolean oldNode,

        /** status=resolved。 */
        boolean resolved,

        /** 有非 trivial 推演结果。 */
        boolean hasSimulationResult,

        /** input.md 有非空内容。 */
        boolean hasInput,

        /** input.md 非空行数。 */
        int inputLineCount,

        /** 总消息块数。 */
        int messageCount,

        /** chat_user 消息数。 */
        int chatUserCount,

        /** chat_response 消息数。 */
        int chatResponseCount,

        /** sim_user 消息数。 */
        int simUserCount,

        /** sim_response 消息数。 */
        int simResponseCount,

        /** tool_call 消息数。 */
        int toolCallCount,

        /** tool_result 消息数。 */
        int toolResultCount,

        /** 直接子分支数。 */
        int childBranchCount,

        /** 子分支摘要列表。 */
        List<BranchChildSummary> children,

        /** 兄弟分支数。 */
        int siblingBranchCount,

        /** 父链长度。 */
        int parentChainLength,

        /** 实体数。 */
        int entityCount,

        /** 正式玩家数。 */
        int playerCount,

        /** 规则章节数。 */
        int ruleSectionCount,

        /** 世界观章节数。 */
        int worldSectionCount,

        /** 实体名称列表。 */
        List<String> entityNames,

        /** 正式玩家名称列表。 */
        List<String> playerNames,

        /** 风险摘要（来自分支九）。 */
        String riskSummary,

        /** 下一步建议行动。 */
        String nextActionHint
) {}
