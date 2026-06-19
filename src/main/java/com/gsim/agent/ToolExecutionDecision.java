package com.gsim.agent;

/**
 * 工具执行决策 — 由执行前门禁返回。
 */
public record ToolExecutionDecision(
        ToolExecutionDecisionType decision,
        String reason,
        ToolCategory category,
        boolean allowedByRoute) {

    /** 便捷工厂 */
    public static ToolExecutionDecision allow(String reason, ToolCategory cat, boolean allowedByRoute) {
        return new ToolExecutionDecision(ToolExecutionDecisionType.ALLOW, reason, cat, allowedByRoute);
    }

    public static ToolExecutionDecision needConfirmation(String reason, ToolCategory cat, boolean allowedByRoute) {
        return new ToolExecutionDecision(ToolExecutionDecisionType.NEED_CONFIRMATION, reason, cat, allowedByRoute);
    }

    public static ToolExecutionDecision reject(String reason, ToolCategory cat, boolean allowedByRoute) {
        return new ToolExecutionDecision(ToolExecutionDecisionType.REJECT, reason, cat, allowedByRoute);
    }
}
