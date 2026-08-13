package com.gsim.session;

/**
 * 会话节点类型 — 统一标识 SessionPool 中的各类交互事件。
 */
public enum NodeType {

    /** 用户输入（文本、命令） */
    USER_INPUT,

    /** LLM 流式输出（payload.content 持续增长） */
    LLM_STREAMING,

    /** 工具调用（含参数 + 结果） */
    TOOL_CALL,

    /** Agent 纯文本输出（非流式，如总结、错误提示） */
    AGENT_MESSAGE,

    /** 系统事件（错误、状态变更） */
    SYSTEM
}
