package com.gsim.agent;

/**
 * 工具执行决策类型。
 */
public enum ToolExecutionDecisionType {
    /** 允许立即执行，无需确认 */
    ALLOW,
    /** 需要用户确认 */
    NEED_CONFIRMATION,
    /** 拒绝执行 */
    REJECT
}
