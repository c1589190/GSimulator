package com.gsim.data;

/**
 * Branch 更新数据 — 用于 /sim 覆盖当前 branch 的章节内容。
 */
public record BranchUpdate(
        String nodeInput,
        String llmContextLog,
        String simulationResult,
        String worldDelta,
        String entityDelta,
        String ruleDelta,
        String interactionDelta,
        String skillDelta,
        String nextRisks
) {
    public static BranchUpdate empty() {
        return new BranchUpdate("无。", "### user\n\n无。\n", "待推演。",
                "无。", "无。", "无。", "无。", "无。", "待后续推演。");
    }
}
