package com.gsim.agent;

/**
 * ExpectedNextStep -- Agent 期望的下一步动作类型。
 *
 * <p>Agent 执行过程中，ToolLoop 根据当前状态判断 LLM 应执行的操作：
 * 继续调用工具收集信息，或是调用 finish_action 结束本轮。
 */
public enum ExpectedNextStep {
    /** LLM 应调用工具获取所需信息 */
    CALL_TOOL,
    /** LLM 已有足够工具结果，必须调用 finish_action 结束本轮 */
    FINISH_ACTION
}
