package com.gsim.session;

/**
 * 会话节点状态机：PENDING → STREAMING → DONE | ERROR。
 */
public enum NodeStatus {

    /** 节点已创建，尚未开始处理 */
    PENDING,

    /** 节点正在流式更新中（如 LLM 逐 token 输出） */
    STREAMING,

    /** 节点已完成 */
    DONE,

    /** 节点执行出错 */
    ERROR
}
