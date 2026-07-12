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

        // 唤醒等待者（仅 USER_INPUT 节点）
        if (type == NodeType.USER_INPUT) {
            CompletableFuture<SessionNode> waiter = waiters.remove(sessionId);
            if (waiter != null) {
                waiter.complete(node);
            }
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
     * 安全处理 content 可能是 StringBuilder（流式写入中）或 String（已完成）的情况。
     */
    public void appendContent(String nodeId, String delta) {
        SessionNode node = nodeIndex.get(nodeId);
        if (node == null) return;
        synchronized (node) {
            Object content = node.payload().get("content");
            if (content instanceof StringBuilder sb) {
                sb.append(delta);
            } else if (content instanceof String s) {
                node.payload().put("content", s + delta);
            } else {
                node.payload().put("content", delta);
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
        NodeStatus oldStatus = parseStatus(node.payload().getOrDefault("status", NodeStatus.PENDING));
        node.payload().put("status", newStatus);
        notifyListeners(node.sessionId(),
                l -> l.onStatusChanged(node, oldStatus, newStatus));
    }

    /** 安全解析 NodeStatus — 兼容 enum 和 String，防止 ClassCastException。 */
    private static NodeStatus parseStatus(Object raw) {
        if (raw instanceof NodeStatus s) return s;
        if (raw instanceof String s) {
            try { return NodeStatus.valueOf(s); }
            catch (IllegalArgumentException e) { return NodeStatus.PENDING; }
        }
        if (raw == null) return NodeStatus.PENDING;
        return NodeStatus.PENDING;
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
     *
     * @throws IllegalStateException 如果该 session 已有等待者
     */
    public CompletableFuture<SessionNode> waitForUserInput(String sessionId) {
        CompletableFuture<SessionNode> future = new CompletableFuture<>();
        CompletableFuture<SessionNode> old = waiters.putIfAbsent(sessionId, future);
        if (old != null) {
            throw new IllegalStateException(
                    "Another waiter already exists for session: " + sessionId);
        }
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
        CompletableFuture<SessionNode> waiter = waiters.remove(sessionId);
        if (waiter != null) {
            waiter.completeExceptionally(
                    new IllegalStateException("Session cleared while waiting: " + sessionId));
        }
    }

    /** 移除单个节点。 */
    public boolean removeNode(String nodeId) {
        SessionNode node = nodeIndex.remove(nodeId);
        if (node == null) return false;
        List<SessionNode> nodes = sessions.get(node.sessionId());
        if (nodes != null) {
            nodes.removeIf(n -> n.nodeId().equals(nodeId));
        }
        return true;
    }

    /** 移除指定 session 中所有已完成/出错/取消的 LLM_STREAMING 节点。 */
    public int pruneCompletedStreamNodes(String sessionId) {
        List<SessionNode> nodes = sessions.get(sessionId);
        if (nodes == null) return 0;
        int removed = 0;
        var it = nodes.iterator();
        while (it.hasNext()) {
            SessionNode n = it.next();
            if (n.type() != NodeType.LLM_STREAMING) continue;
            Object s = n.payload().get("status");
            if (s == NodeStatus.DONE || s == NodeStatus.ERROR) {
                it.remove();
                nodeIndex.remove(n.nodeId());
                removed++;
            }
        }
        return removed;
    }
}
