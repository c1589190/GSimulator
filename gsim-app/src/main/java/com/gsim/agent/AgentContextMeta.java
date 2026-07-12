package com.gsim.agent;

import java.util.Map;

/**
 * Agent 上下文元数据，由 NodeAgentChatService / ContextSessionManager
 * 结构化构造后传入 OrchestratorAgent。
 * 禁止从 markdown 文本反解析这些字段。
 */
public record AgentContextMeta(
        String activeRoot,
        String activeBranch,
        String contextMode,
        boolean fullWorldContextLoaded,
        String contextModeReason,
        java.util.List<String> branchPath,
        java.util.List<String> loadedParentBranches,
        boolean currentBranchLoaded
) {
    public static AgentContextMeta empty() {
        return new AgentContextMeta("unknown", "unknown", "UNKNOWN",
                false, "no_meta_provided",
                java.util.List.of(), java.util.List.of(), false);
    }
}
