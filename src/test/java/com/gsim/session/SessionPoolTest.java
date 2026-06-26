package com.gsim.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SessionPool 统一会话节点池")
class SessionPoolTest {

    private SessionPool pool;

    @BeforeEach
    void setUp() {
        pool = new SessionPool();
    }

    // ===== pushNode =====

    @Test
    @DisplayName("pushNode 创建节点并可通过 getNodes 查询")
    void pushNodeCreatesAndQueries() {
        SessionNode node = pool.pushNode("s1", NodeType.USER_INPUT,
                Map.of("text", "hello"));

        assertNotNull(node.nodeId());
        assertEquals("s1", node.sessionId());
        assertEquals(NodeType.USER_INPUT, node.type());
        assertEquals("hello", node.payload().get("text"));

        List<SessionNode> nodes = pool.getNodes("s1");
        assertEquals(1, nodes.size());
        assertEquals(node.nodeId(), nodes.get(0).nodeId());
    }

    @Test
    @DisplayName("pushNode 带 parentId 建立因果链")
    void pushNodeWithParentId() {
        SessionNode parent = pool.pushNode("s1", NodeType.USER_INPUT,
                Map.of("text", "查询"));
        SessionNode child = pool.pushNode("s1", parent.nodeId(), NodeType.TOOL_CALL,
                Map.of("tool", "query"));

        assertEquals(parent.nodeId(), child.parentId());
        assertNull(parent.parentId());
    }

    @Test
    @DisplayName("pushNode 按顺序排列")
    void pushNodePreservesOrder() {
        pool.pushNode("s1", NodeType.USER_INPUT, Map.of("seq", 1));
        pool.pushNode("s1", NodeType.TOOL_CALL, Map.of("seq", 2));
        pool.pushNode("s1", NodeType.AGENT_MESSAGE, Map.of("seq", 3));

        List<SessionNode> nodes = pool.getNodes("s1");
        assertEquals(3, nodes.size());
        assertEquals(1, nodes.get(0).payload().get("seq"));
        assertEquals(2, nodes.get(1).payload().get("seq"));
        assertEquals(3, nodes.get(2).payload().get("seq"));
    }

    @Test
    @DisplayName("pushNode 不同 session 隔离")
    void pushNodeSessionIsolation() {
        pool.pushNode("s1", NodeType.USER_INPUT, Map.of("text", "from s1"));
        pool.pushNode("s2", NodeType.USER_INPUT, Map.of("text", "from s2"));

        assertEquals(1, pool.getNodes("s1").size());
        assertEquals(1, pool.getNodes("s2").size());
        assertEquals("from s1", pool.getNodes("s1").get(0).payload().get("text"));
        assertEquals("from s2", pool.getNodes("s2").get(0).payload().get("text"));
    }

    // ===== updateNode / appendContent =====

    @Test
    @DisplayName("updateNode 更新 payload")
    void updateNodeUpdatesPayload() {
        SessionNode node = pool.pushNode("s1", NodeType.LLM_STREAMING,
                new ConcurrentHashMap<>());

        pool.updateNode(node.nodeId(), "model", "deepseek-v4");

        SessionNode updated = pool.nodeById(node.nodeId());
        assertEquals("deepseek-v4", updated.payload().get("model"));
    }

    @Test
    @DisplayName("appendContent 逐次追加字符串")
    void appendContentAccumulates() {
        SessionNode node = pool.pushNode("s1", NodeType.LLM_STREAMING,
                new ConcurrentHashMap<>());

        pool.appendContent(node.nodeId(), "曹操");
        pool.appendContent(node.nodeId(), "自陈留起兵");

        String content = pool.getContent(node.nodeId());
        assertTrue(content.contains("曹操"));
        assertTrue(content.contains("自陈留起兵"));
    }

    @Test
    @DisplayName("getContent 在 LLM_STREAM_COMPLETED 后返回完整字符串")
    void getContentReturnsFullContent() {
        SessionNode node = pool.pushNode("s1", NodeType.LLM_STREAMING,
                new ConcurrentHashMap<>());
        pool.appendContent(node.nodeId(), "Hello ");

        // simulate bridge doing flush
        Object raw = node.payload().get("content");
        if (raw instanceof StringBuilder sb) {
            node.payload().put("content", sb.toString());
        }

        String content = pool.getContent(node.nodeId());
        assertEquals("Hello ", content);
    }

    // ===== transitionStatus =====

    @Test
    @DisplayName("transitionStatus 变更节点状态")
    void transitionStatusChangesStatus() {
        SessionNode node = pool.pushNode("s1", NodeType.TOOL_CALL,
                new ConcurrentHashMap<>());
        node.payload().put("status", NodeStatus.PENDING);

        pool.transitionStatus(node.nodeId(), NodeStatus.DONE);

        assertEquals(NodeStatus.DONE, node.payload().get("status"));
    }

    // ===== getNodesSince =====

    @Test
    @DisplayName("getNodesSince 只返回时间点之后的节点")
    void getNodesSinceFiltersByTime() throws Exception {
        pool.pushNode("s1", NodeType.USER_INPUT, Map.of("seq", 1));
        Thread.sleep(10);
        Instant since = Instant.now();
        Thread.sleep(10);
        pool.pushNode("s1", NodeType.TOOL_CALL, Map.of("seq", 2));
        pool.pushNode("s1", NodeType.AGENT_MESSAGE, Map.of("seq", 3));

        List<SessionNode> recent = pool.getNodesSince("s1", since);
        assertEquals(2, recent.size());
    }

    // ===== subscribe / unsubscribe =====

    @Test
    @DisplayName("subscribe 后 pushNode 触发 onNodePushed")
    void subscribeReceivesPushEvents() {
        AtomicInteger count = new AtomicInteger();
        AtomicReference<SessionNode> captured = new AtomicReference<>();

        pool.subscribe("s1", new SessionNodeListener() {
            @Override
            public void onNodePushed(SessionNode node) {
                count.incrementAndGet();
                captured.set(node);
            }
        });

        pool.pushNode("s1", NodeType.USER_INPUT, Map.of("text", "hi"));

        assertEquals(1, count.get());
        assertNotNull(captured.get());
        assertEquals("hi", captured.get().payload().get("text"));
    }

    @Test
    @DisplayName("subscribe 后 updateNode 触发 onNodeUpdated")
    void subscribeReceivesUpdateEvents() {
        SessionNode node = pool.pushNode("s1", NodeType.LLM_STREAMING,
                new ConcurrentHashMap<>());
        AtomicReference<String> updatedKey = new AtomicReference<>();

        pool.subscribe("s1", new SessionNodeListener() {
            @Override
            public void onNodePushed(SessionNode n) {}

            @Override
            public void onNodeUpdated(SessionNode n, String key, Object newValue) {
                updatedKey.set(key);
            }
        });

        pool.updateNode(node.nodeId(), "temperature", 0.3);

        assertEquals("temperature", updatedKey.get());
    }

    @Test
    @DisplayName("subscribe 后 transitionStatus 触发 onStatusChanged")
    void subscribeReceivesStatusEvents() {
        SessionNode node = pool.pushNode("s1", NodeType.TOOL_CALL,
                new ConcurrentHashMap<>());
        node.payload().put("status", NodeStatus.PENDING);
        AtomicReference<NodeStatus> newStatus = new AtomicReference<>();

        pool.subscribe("s1", new SessionNodeListener() {
            @Override
            public void onNodePushed(SessionNode n) {}

            @Override
            public void onStatusChanged(SessionNode n, NodeStatus old, NodeStatus nw) {
                newStatus.set(nw);
            }
        });

        pool.transitionStatus(node.nodeId(), NodeStatus.DONE);

        assertEquals(NodeStatus.DONE, newStatus.get());
    }

    @Test
    @DisplayName("unsubscribe 后不再收到事件")
    void unsubscribeStopsEvents() {
        AtomicInteger count = new AtomicInteger();
        SessionNodeListener listener = node -> count.incrementAndGet();

        pool.subscribe("s1", listener);
        pool.pushNode("s1", NodeType.USER_INPUT, Map.of());
        assertEquals(1, count.get());

        pool.unsubscribe("s1", listener);
        pool.pushNode("s1", NodeType.USER_INPUT, Map.of());
        assertEquals(1, count.get()); // unchanged
    }

    @Test
    @DisplayName("不同 session 的 listener 隔离")
    void listenerSessionIsolation() {
        AtomicInteger s1Count = new AtomicInteger();
        AtomicInteger s2Count = new AtomicInteger();

        pool.subscribe("s1", node -> s1Count.incrementAndGet());
        pool.subscribe("s2", node -> s2Count.incrementAndGet());

        pool.pushNode("s1", NodeType.USER_INPUT, Map.of());

        assertEquals(1, s1Count.get());
        assertEquals(0, s2Count.get());
    }

    // ===== waitForUserInput =====

    @Test
    @DisplayName("waitForUserInput 在 USER_INPUT 节点推入时完成")
    void waitForUserInputCompletesOnPush() throws Exception {
        CompletableFuture<SessionNode> future = pool.waitForUserInput("s1");

        // push USER_INPUT node in another thread
        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) {}
            pool.pushNode("s1", NodeType.USER_INPUT, Map.of("text", "玩家输入"));
        });

        SessionNode node = future.get(5, TimeUnit.SECONDS);
        assertNotNull(node);
        assertEquals(NodeType.USER_INPUT, node.type());
        assertEquals("玩家输入", node.payload().get("text"));
    }

    @Test
    @DisplayName("非 USER_INPUT 节点不触发 waitForUserInput")
    void nonUserInputDoesNotTriggerWaiter() {
        CompletableFuture<SessionNode> future = pool.waitForUserInput("s1");

        pool.pushNode("s1", NodeType.TOOL_CALL, Map.of("tool", "test"));
        pool.pushNode("s1", NodeType.AGENT_MESSAGE, Map.of("msg", "test"));

        assertFalse(future.isDone());
    }

    // ===== clearSession =====

    @Test
    @DisplayName("clearSession 清理所有节点和监听器")
    void clearSessionRemovesAll() {
        pool.pushNode("s1", NodeType.USER_INPUT, Map.of());
        pool.pushNode("s1", NodeType.TOOL_CALL, Map.of());
        AtomicInteger count = new AtomicInteger();
        pool.subscribe("s1", node -> count.incrementAndGet());

        pool.clearSession("s1");

        assertTrue(pool.getNodes("s1").isEmpty());
        pool.pushNode("s1", NodeType.USER_INPUT, Map.of());
        assertEquals(0, count.get()); // listener also removed
    }

    // ===== nodeById =====

    @Test
    @DisplayName("nodeById 查找已存在的节点")
    void nodeByIdFindsExisting() {
        SessionNode node = pool.pushNode("s1", NodeType.USER_INPUT, Map.of("id", "test"));
        SessionNode found = pool.nodeById(node.nodeId());
        assertNotNull(found);
        assertEquals(node.nodeId(), found.nodeId());
    }

    @Test
    @DisplayName("nodeById 对不存在的节点返回 null")
    void nodeByIdReturnsNullForMissing() {
        assertNull(pool.nodeById("nonexistent"));
    }

    // ===== 状态机测试 =====

    @Test
    @DisplayName("LLM 流式节点状态机: PENDING → STREAMING → DONE")
    void llmStreamingStateMachine() {
        SessionNode node = pool.pushNode("s1", NodeType.LLM_STREAMING,
                new ConcurrentHashMap<>());

        pool.transitionStatus(node.nodeId(), NodeStatus.PENDING);
        assertEquals(NodeStatus.PENDING, node.payload().get("status"));

        pool.transitionStatus(node.nodeId(), NodeStatus.STREAMING);
        assertEquals(NodeStatus.STREAMING, node.payload().get("status"));

        pool.transitionStatus(node.nodeId(), NodeStatus.DONE);
        assertEquals(NodeStatus.DONE, node.payload().get("status"));
    }

    @Test
    @DisplayName("工具调用节点状态机: PENDING → STREAMING → DONE | ERROR")
    void toolCallStateMachine() {
        SessionNode node = pool.pushNode("s1", NodeType.TOOL_CALL,
                new ConcurrentHashMap<>());

        pool.transitionStatus(node.nodeId(), NodeStatus.PENDING);
        pool.transitionStatus(node.nodeId(), NodeStatus.STREAMING);
        pool.transitionStatus(node.nodeId(), NodeStatus.ERROR);

        assertEquals(NodeStatus.ERROR, node.payload().get("status"));
    }
}
