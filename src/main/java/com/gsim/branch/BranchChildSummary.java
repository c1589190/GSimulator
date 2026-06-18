package com.gsim.branch;

/**
 * 子分支摘要 — 一个直接子 branch 的关键信息。
 */
public record BranchChildSummary(
        /** 分支 ID，如 branch.b0001-contact。 */
        String branchId,

        /** 分支名，如 "首次接触"。 */
        String name,

        /** 回合号。 */
        int turn,

        /** 世界时间字符串。 */
        String worldTime,

        /** front matter status。 */
        String status,

        /** 是否有非 trivial 的推演结果。 */
        boolean hasSimulationResult,

        /** 消息块数量。 */
        int messageCount,

        /** 最后更新（来自 front matter）。 */
        String updated
) {}
