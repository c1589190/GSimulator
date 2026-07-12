package com.gsim.agent.core;

import java.util.List;

/**
 * Agent 完整执行结果 — 包含全部轮次记录，不只是最终文本。
 */
public record AgentResult(
        String agentId,
        boolean success,
        String finalText,
        List<AgentRound> rounds,
        int totalToolCalls,
        String error,
        String cacheSessionId       // 该 Agent 对应 Cache 的 sessionId（文件名）
) {
    public static AgentResult ok(String agentId, String finalText, List<AgentRound> rounds, int totalToolCalls) {
        return new AgentResult(agentId, true, finalText, rounds, totalToolCalls, null, null);
    }

    public static AgentResult ok(String agentId, String finalText, List<AgentRound> rounds,
                                  int totalToolCalls, String cacheSessionId) {
        return new AgentResult(agentId, true, finalText, rounds, totalToolCalls, null, cacheSessionId);
    }

    public static AgentResult fail(String agentId, String error) {
        return new AgentResult(agentId, false, null, List.of(), 0, error, null);
    }

    public static AgentResult fail(String agentId, String error, String cacheSessionId) {
        return new AgentResult(agentId, false, null, List.of(), 0, error, cacheSessionId);
    }
}
