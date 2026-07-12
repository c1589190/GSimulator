package com.gsim.agent;

/**
 * 用户对工具确认请求的选择。
 */
public enum ConfirmationChoice {
    /** 仅本次允许 */
    ALLOW_ONCE,
    /** 本轮后续 MUTATING 工具都允许（DESTRUCTIVE 除外） */
    ALLOW_ALL_THIS_TURN,
    /** 拒绝，停止本轮 */
    DENY
}
