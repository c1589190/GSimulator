package com.gsim.agent;

/**
 * ConfirmationChoice -- 用户对工具确认请求的选择。
 *
 * <p>用户通过 CLI 或 API 对工具调用做出的批准/拒绝决定。
 */
public enum ConfirmationChoice {
    /** 仅本次允许该工具执行 */
    ALLOW_ONCE,
    /** 本轮后续 MUTATING 工具都允许（DESTRUCTIVE 除外） */
    ALLOW_ALL_THIS_TURN,
    /** 拒绝该工具，停止本轮执行 */
    DENY
}
