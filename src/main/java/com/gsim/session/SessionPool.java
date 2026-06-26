package com.gsim.session;

import com.gsim.util.IdGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 统一会话节点池 — 所有交互事件（用户输入、LLM 流式输出、工具调用、Agent 消息、系统事件）
 * 都作为 {@link SessionNode} 注册到池中，支持实时流式更新和订阅监听。
 *
 * <p>线程安全：使用 ConcurrentHashMap + CopyOnWriteArrayList。
 */
public class SessionPool {

    /** sessionId → 有序节点列表 */
    private final Map<String, List<SessionNode>> sessions = new ConcurrentHashMap<>();

    /** sessionId → 订阅监听器列表 */
    private final Map<String, List<SessionNodeListener>> listeners = new ConcurrentHashMap<>();

    /** nodeId → SessionNode 快速索引 */
    private final Map<String, SessionNode> nodeIndex = new ConcurrentHashMap<>();

    /** sessionId → 等待用户输入的 Future */
    private final Map<String, CompletableFuture<SessionNode>> waiters = new ConcurrentHashMap<>();

    // ===== 节点操作 =====

    /**
     * 向指定会话推送一个新节点。
     *
     * @return 新创建的 SessionNode
     */
    public SessionNode pushNode(String sessionId, NodeType type, Map<String, Object> payload) {
        return pushNode(sessionId, null, type, payload);
    }

    /**
     * 向指定会话推送一个新节点，带 parentId（因果链）。
     */
    public SessionNode pushNode(String sessionId, String parentId, NodeType type,
                                  Map<String, Object> payload) {
        String nodeId = IdGenerator.generate("node");
        SessionNode node = new SessionNode(nodeId, sessionId, parentId, type,
                Instant.now(), payload);

        sessions.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(node);
        nodeIndex.put(nodeId, node);

        // 唤醒等待者（如用户输入节点）
        CompletableFuture<SessionNode> waiter = waiters.remove(sessionId);
        if (waiter != null && type == NodeType.USER_INPUT) {
            waiter.complete(node);
        }

        // 通知订阅者
        notifyListeners(sessionId, l -> l.onNodePushed(node));

        return node;
    }

    /**
     * 更新节点 payload 中指定 key 的值（线程安全）。
     */
    public void updateNode(String nodeId, String key, Object value) {
        SessionNode node = nodeIndex.get(nodeId);
        if (node == null) return;
        node.payload().put(key, value);
        notifyListeners(node.sessionId(), l -> l.onNodeUpdated(node, key, value));
    }

    /**
     * 向节点的 content key 追加字符串（LLM delta 场景）。
     */
    public void appendContent(String nodeId, String delta) {
        SessionNode node = nodeIndex.get(nodeId);
        if (node == null) return;
        synchronized (node) {
            @SuppressWarnings("unchecked")
            StringBuilder sb = (StringBuilder) node.payload()
                    .computeIfAbsent("content", k -> new StringBuilder());
            if (sb instanceof StringBuilder) {
                sb.append(delta);
            }
        }
        notifyListeners(node.sessionId(), l -> l.onNodeUpdated(node, "content", delta));
    }

    /**
     * 获取节点的完整 content（从 StringBuilder 拼接）。
     */
    public String getContent(String nodeId) {
        SessionNode node = nodeIndex.get(nodeId);
        if (node == null) return "";
        Object content = node.payload().get("content");
        return content != null ? content.toString() : "";
    }

    /**
     * 变更节点状态。
     */
    public void transitionStatus(String nodeId, NodeStatus newStatus) {
        SessionNode node = nodeIndex.get(nodeId);
        if (node == null) return;
        NodeStatus oldStatus = (NodeStatus) node.payload().getOrDefault("status", NodeStatus.PENDING);
        node.payload().put("status", newStatus);
        notifyListeners(node.sessionId(),
                l -> l.onStatusChanged(node, oldStatus, newStatus));
    }

    // ===== 查询 =====

    /** 返回指定会话的所有节点（按插入顺序）。 */
    public List<SessionNode> getNodes(String sessionId) {
        return List.copyOf(sessions.getOrDefault(sessionId, List.of()));
    }

    /** 返回指定会话中，在给定时间后创建的所有节点。 */
    public List<SessionNode> getNodesSince(String sessionId, Instant since) {
        return sessions.getOrDefault(sessionId, List.of()).stream()
                .filter(n -> n.createdAt().isAfter(since))
                .toList();
    }

    /** 根据 nodeId 查找节点。 */
    public SessionNode nodeById(String nodeId) {
        return nodeIndex.get(nodeId);
    }

    // ===== 订阅 =====

    /** 订阅指定会话的节点变更事件。 */
    public void subscribe(String sessionId, SessionNodeListener listener) {
        listeners.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** 取消订阅。 */
    public void unsubscribe(String sessionId, SessionNodeListener listener) {
        List<SessionNodeListener> list = listeners.get(sessionId);
        if (list != null) list.remove(listener);
    }

    // ===== 等待用户输入 =====

    /**
     * 异步等待该会话的下一个 USER_INPUT 节点（Agent 阻塞/异步等待）。
     */
    public CompletableFuture<SessionNode> waitForUserInput(String sessionId) {
        CompletableFuture<SessionNode> future = new CompletableFuture<>();
        waiters.put(sessionId, future);
        return future;
    }

    // ===== 内部 =====

    private void notifyListeners(String sessionId,
                                   java.util.function.Consumer<SessionNodeListener> action) {
        List<SessionNodeListener> list = listeners.get(sessionId);
        if (list != null) {
            for (SessionNodeListener l : list) {
                try {
                    action.accept(l);
                } catch (Exception e) {
                    // don't let one broken listener kill others
                }
            }
        }
    }

    /** 移除指定 session 的所有节点（清理用）。 */
    public void clearSession(String sessionId) {
        List<SessionNode> nodes = sessions.remove(sessionId);
        if (nodes != null) {
            for (SessionNode n : nodes) nodeIndex.remove(n.nodeId());
        }
        listeners.remove(sessionId);
        waiters.remove(sessionId);
    }
}
