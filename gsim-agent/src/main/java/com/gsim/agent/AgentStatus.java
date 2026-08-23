package com.gsim.agent;

/**
 * AgentStatus -- Agent 运行时状态枚举。
 *
 * <p>定义 Agent 实例的完整生命周期状态，包括等待、运行中、已完成、失败和已取消。
 */
public enum AgentStatus {
    /** Agent 已创建，等待执行 */
    PENDING,
    /** Agent 正在运行中 */
    RUNNING,
    /** Agent 正常执行完毕 */
    DONE,
    /** Agent 执行失败 */
    FAILED,
    /** Agent 被用户或系统取消 */
    CANCELLED
}
