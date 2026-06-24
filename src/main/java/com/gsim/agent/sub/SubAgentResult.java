package com.gsim.agent.sub;

/**
 * SubAgent 执行结果。
 */
public record SubAgentResult(
        String agentId,
        boolean success,
        String text,
        String error
) {
    public static SubAgentResult ok(String agentId, String text) {
        return new SubAgentResult(agentId, true, text, null);
    }

    public static SubAgentResult fail(String agentId, String error) {
        return new SubAgentResult(agentId, false, null, error);
    }
}
