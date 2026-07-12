package com.gsim.agent;

/**
 * 期望的下一步动作。
 */
public enum ExpectedNextStep {
    /** LLM 应调用工具获取所需信息 */
    CALL_TOOL,
    /** LLM 已有足够工具结果，必须调用 finish_action */
    FINISH_ACTION
}
