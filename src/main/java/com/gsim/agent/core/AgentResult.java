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
        String error
) {
    public static AgentResult ok(String agentId, String finalText, List<AgentRound> rounds, int totalToolCalls) {
        return new AgentResult(agentId, true, finalText, rounds, totalToolCalls, null);
    }

    public static AgentResult fail(String agentId, String error) {
        return new AgentResult(agentId, false, null, List.of(), 0, error);
    }
}
