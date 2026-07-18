package com.gsim.session;

/**
 * 会话节点监听器 — 订阅 SessionPool 中节点的变更事件。
 *
 * <p>三个回调分别对应：新节点推入、节点内容更新、节点状态变更。
 * 所有默认实现均为空操作。
 */
@FunctionalInterface
public interface SessionNodeListener {

    /**
     * 新节点被推入池中。
     *
     * @param node 被推入的会话节点
     */
    void onNodePushed(SessionNode node);

    /**
     * 节点 payload 中某个 key 被更新（如 LLM delta 追加 content）。
     *
     * @param node     被更新的会话节点
     * @param key      变更的 payload key
     * @param newValue 变更后的值
     */
    default void onNodeUpdated(SessionNode node, String key, Object newValue) {}

    /**
     * 节点状态变更（PENDING → STREAMING → DONE | ERROR）。
     *
     * @param node      发生状态变更的会话节点
     * @param oldStatus 变更前的状态
     * @param newStatus 变更后的状态
     */
    default void onStatusChanged(SessionNode node, NodeStatus oldStatus, NodeStatus newStatus) {}
}
