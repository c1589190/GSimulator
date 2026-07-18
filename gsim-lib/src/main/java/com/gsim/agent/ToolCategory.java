package com.gsim.agent;

/**
 * 工具调用分类 — 决定执行前门禁和确认需求。
 */
public enum ToolCategory {
    /** 只读：默认允许，无需确认 */
    READ_ONLY,
    /** 写入/变更：默认需要确认 */
    MUTATING,
    /** 破坏性（删除/覆盖）：永远需要确认，不允许"一直允许本轮" */
    DESTRUCTIVE,
    /** 控制流（finish_action）：不需要确认，特殊路由规则 */
    CONTROL
}
