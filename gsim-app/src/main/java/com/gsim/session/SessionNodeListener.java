package com.gsim.session;

/**
 * 会话节点监听器 — 订阅 SessionPool 中节点的变更事件。
 *
 * <p>三个回调分别对应：新节点推入、节点内容更新、节点状态变更。
 * 所有默认实现均为空操作。
 */
@FunctionalInterface
public interface SessionNodeListener {

    /** 新节点被推入池中 */
    void onNodePushed(SessionNode node);

    /** 节点 payload 中某个 key 被更新（如 LLM delta 追加 content） */
    default void onNodeUpdated(SessionNode node, String key, Object newValue) {}

    /** 节点状态变更（PENDING → STREAMING → DONE | ERROR） */
    default void onStatusChanged(SessionNode node, NodeStatus oldStatus, NodeStatus newStatus) {}
}
