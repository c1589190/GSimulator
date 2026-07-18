package com.gsim.agent;

/**
 * AgentContextMeta -- Agent 上下文元数据。
 *
 * <p>由 NodeAgentChatService / ContextSessionManager 结构化构造后传入 OrchestratorAgent。
 * 包含当前活跃 Root、分支、上下文模式等信息。
 * 禁止从 markdown 文本反解析这些字段。
 *
 * @param activeRoot             当前活跃的 Root ID
 * @param activeBranch           当前活跃的分支 ID
 * @param contextMode            上下文模式（如 "FULL", "SUMMARY" 等）
 * @param fullWorldContextLoaded 是否已加载完整世界上下文
 * @param contextModeReason      上下文模式选择的原因说明
 * @param branchPath             分支路径列表
 * @param loadedParentBranches   已加载的父分支列表
 * @param currentBranchLoaded    当前分支是否已加载
 */
public record AgentContextMeta(
        String activeRoot,
        String activeBranch,
        String contextMode,
        boolean fullWorldContextLoaded,
        String contextModeReason,
        java.util.List<String> branchPath,
        java.util.List<String> loadedParentBranches,
        boolean currentBranchLoaded) {
    public static AgentContextMeta empty() {
        return new AgentContextMeta(
                "unknown",
                "unknown",
                "UNKNOWN",
                false,
                "no_meta_provided",
                java.util.List.of(),
                java.util.List.of(),
                false);
    }
}
