package com.gsim.branch;

/**
 * 节点年龄状态分类。
 * 按内容状态判断，非按创建时间判断。
 */
public enum NodeAgeStatus {
    /** 刚创建，无输入、无推演、无对话。 */
    NEW_EMPTY,

    /** 有输入但尚未推演。 */
    NEW_WITH_INPUT,

    /** 有多轮对话但无推演结果。 */
    DISCUSSION_NODE,

    /** 已有推演结果。 */
    SIMULATED_NODE,

    /** 已有子分支的老节点。 */
    BRANCHED_OLD_NODE,

    /** 无法判断。 */
    UNKNOWN
}
