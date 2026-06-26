package com.gsim.session;

import com.gsim.agent.AgentProgressEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SessionPoolBridge AgentProgressEvent → SessionNode 桥接")
class SessionPoolBridgeTest {

    private SessionPool pool;
    private SessionPoolBridge bridge;

    @BeforeEach
    void setUp() {
        pool = new SessionPool();
        bridge = new SessionPoolBridge(pool, "test-session");
    }

    // ===== LLM 流式 =====

    @Test
    @DisplayName("LLM_STREAM_STARTED → pushNode(LLM_STREAMING)")
    void llmStreamStartedCreatesNode() {
        bridge.onProgress(AgentProgressEvent.llmStreamStarted("stream-1"));

        List<SessionNode> nodes = pool.getNodes("test-session");
        assertEquals(1, nodes.size());
        assertEquals(NodeType.LLM_STREAMING, nodes.get(0).type());
        assertEquals("stream-1", nodes.get(0).payload().get("streamId"));
    }

    @Test
    @DisplayName("LLM_CONTENT_DELTA → appendContent")
    void llmContentDeltaAppends() {
        bridge.onProgress(AgentProgressEvent.llmStreamStarted("stream-1"));
        bridge.onProgress(AgentProgressEvent.llmContentDelta("stream-1", "曹操"));
        bridge.onProgress(AgentProgressEvent.llmContentDelta("stream-1", "起兵"));

        List<SessionNode> nodes = pool.getNodes("test-session");
        assertEquals(1, nodes.size()); // same node, not new
        String content = pool.getContent(nodes.get(0).nodeId());
        assertTrue(content.contains("曹操"));
        assertTrue(content.contains("起兵"));
    }

    @Test
    @DisplayName("LLM_STREAM_COMPLETED → transitionStatus(DONE)")
    void llmStreamCompletedTransitionsStatus() {
        bridge.onProgress(AgentProgressEvent.llmStreamStarted("stream-1"));
        bridge.onProgress(AgentProgressEvent.llmContentDelta("stream-1", "测试"));
        bridge.onProgress(AgentProgressEvent.llmStreamCompleted("stream-1"));

        List<SessionNode> nodes = pool.getNodes("test-session");
        assertEquals(NodeStatus.DONE, nodes.get(0).payload().get("status"));
        // content should be a String (flushed from StringBuilder)
        Object content = nodes.get(0).payload().get("content");
        assertInstanceOf(String.class, content);
    }

    @Test
    @DisplayName("LLM_STREAM_FAILED → transitionStatus(ERROR)")
    void llmStreamFailedTransitionsToError() {
        bridge.onProgress(AgentProgressEvent.llmStreamStarted("stream-1"));
        bridge.onProgress(AgentProgressEvent.llmStreamFailed("stream-1", "API timeout"));

        List<SessionNode> nodes = pool.getNodes("test-session");
        assertEquals(NodeStatus.ERROR, nodes.get(0).payload().get("status"));
        assertNotNull(nodes.get(0).payload().get("error"));
    }

    // ===== 工具调用 =====

    @Test
    @DisplayName("TOOL_SELECTED → pushNode(TOOL_CALL)")
    void toolSelectedCreatesNode() {
        bridge.onProgress(AgentProgressEvent.toolSelected(1, 5, "query_keyword"));

        List<SessionNode> nodes = pool.getNodes("test-session");
        assertEquals(1, nodes.size());
        assertEquals(NodeType.TOOL_CALL, nodes.get(0).type());
        assertEquals("query_keyword", nodes.get(0).payload().get("tool"));
    }

    @Test
    @DisplayName("TOOL_SUCCESS → 最近 TOOL_CALL 节点 → DONE")
    void toolSuccessMarksDone() {
        bridge.onProgress(AgentProgressEvent.toolSelected(1, 5, "query_keyword"));
        bridge.onProgress(AgentProgressEvent.toolSuccess(1, 5, "query_keyword"));

        List<SessionNode> nodes = pool.getNodes("test-session");
        assertEquals(1, nodes.size());
        assertEquals(NodeStatus.DONE, nodes.get(0).payload().get("status"));
        assertEquals("success", nodes.get(0).payload().get("result"));
    }

    @Test
    @DisplayName("TOOL_FAILED → 最近 TOOL_CALL 节点 → ERROR")
    void toolFailedMarksError() {
        bridge.onProgress(AgentProgressEvent.toolSelected(1, 5, "bad_tool"));
        bridge.onProgress(AgentProgressEvent.toolFailed(1, 5, "bad_tool", "not found"));

        List<SessionNode> nodes = pool.getNodes("test-session");
        // should have TOOL_CALL (ERROR) + SYSTEM (error message)
        List<SessionNode> toolNodes = nodes.stream()
                .filter(n -> n.type() == NodeType.TOOL_CALL)
                .toList();
        assertEquals(1, toolNodes.size());
        assertEquals(NodeStatus.ERROR, toolNodes.get(0).payload().get("status"));
        assertEquals("failed", toolNodes.get(0).payload().get("result"));
    }

    // ===== Agent 公开消息 =====

    @Test
    @DisplayName("AGENT_PUBLIC_MESSAGE → pushNode(AGENT_MESSAGE, DONE)")
    void publicMessageCreatesNode() {
        bridge.onProgress(AgentProgressEvent.publicMessage("这是一条公开消息"));

        List<SessionNode> nodes = pool.getNodes("test-session");
        assertEquals(1, nodes.size());
        assertEquals(NodeType.AGENT_MESSAGE, nodes.get(0).type());
        assertEquals("这是一条公开消息", nodes.get(0).payload().get("message"));
        assertEquals(NodeStatus.DONE, nodes.get(0).payload().get("status"));
    }

    @Test
    @DisplayName("simulationContent → payload 含 subType/title/body")
    void simulationContentIncludesMetadata() {
        bridge.onProgress(AgentProgressEvent.simulationContent("推文标题", "推文正文内容"));

        List<SessionNode> nodes = pool.getNodes("test-session");
        assertEquals(1, nodes.size());
        assertEquals("simulation_content", nodes.get(0).payload().get("subType"));
        assertEquals("推文标题", nodes.get(0).payload().get("title"));
        assertEquals("推文正文内容", nodes.get(0).payload().get("body"));
    }

    // ===== 控制事件 =====

    @Test
    @DisplayName("FINISH_ACTION_ACCEPTED → pushNode(SYSTEM)")
    void finishActionAcceptedCreatesSystemNode() {
        bridge.onProgress(AgentProgressEvent.finishAccepted(3, 5));

        List<SessionNode> nodes = pool.getNodes("test-session");
        assertEquals(1, nodes.size());
        assertEquals(NodeType.SYSTEM, nodes.get(0).type());
        assertTrue(((String) nodes.get(0).payload().get("message")).contains("finish_action"));
    }

    @Test
    @DisplayName("ABORTED → pushNode(SYSTEM)")
    void abortedCreatesSystemNode() {
        bridge.onProgress(AgentProgressEvent.aborted(2, 5, "用户取消"));

        List<SessionNode> nodes = pool.getNodes("test-session");
        assertEquals(1, nodes.size());
        assertEquals(NodeType.SYSTEM, nodes.get(0).type());
        assertTrue(((String) nodes.get(0).payload().get("message")).contains("中止"));
    }

    // ===== 被动事件不创建节点 =====

    @Test
    @DisplayName("CONTEXT_LOADED / WAITING_LLM 等被动事件不创建节点")
    void passiveEventsDoNotCreateNodes() {
        bridge.onProgress(AgentProgressEvent.contextLoaded(1, 5, 100, 5));
        bridge.onProgress(AgentProgressEvent.waitingLlm(1, 5));
        bridge.onProgress(AgentProgressEvent.awaitingFinishAction(2, 5));
        bridge.onProgress(AgentProgressEvent.plainAnswerWithoutFinish(2, 5));
        bridge.onProgress(AgentProgressEvent.invalidBracketIntent(2, 5));

        List<SessionNode> nodes = pool.getNodes("test-session");
        assertEquals(0, nodes.size()); // none of these should create nodes
    }

    // ===== 完整工作流模拟 =====

    @Test
    @DisplayName("两个连续工具调用应各自更新自己的节点（不交叉）")
    void twoConsecutiveToolCallsUpdateCorrectNodes() {
        // Tool A
        bridge.onProgress(AgentProgressEvent.toolSelected(1, 3, "query_keyword"));
        bridge.onProgress(AgentProgressEvent.toolExecuting(1, 3, "query_keyword"));
        bridge.onProgress(AgentProgressEvent.toolSuccess(1, 3, "query_keyword"));

        // Tool B
        bridge.onProgress(AgentProgressEvent.toolSelected(1, 3, "write_element"));
        bridge.onProgress(AgentProgressEvent.toolExecuting(1, 3, "write_element"));
        bridge.onProgress(AgentProgressEvent.toolFailed(1, 3, "write_element", "permission denied"));

        List<SessionNode> toolNodes = pool.getNodes("test-session").stream()
                .filter(n -> n.type() == NodeType.TOOL_CALL)
                .toList();
        assertEquals(2, toolNodes.size());

        // Tool A should be DONE
        assertEquals(NodeStatus.DONE, toolNodes.get(0).payload().get("status"));
        assertEquals("query_keyword", toolNodes.get(0).payload().get("tool"));

        // Tool B should be ERROR (not mistakenly marked DONE)
        assertEquals(NodeStatus.ERROR, toolNodes.get(1).payload().get("status"));
        assertEquals("write_element", toolNodes.get(1).payload().get("tool"));
    }

    @Test
    @DisplayName("clearSession 清理 bridge 映射")
    void clearSessionCleansBridge() {
        bridge.onProgress(AgentProgressEvent.llmStreamStarted("s1"));
        bridge.onProgress(AgentProgressEvent.toolSelected(1, 3, "test_tool"));
        bridge.clearSession();

        // After clear, these should not throw
        assertDoesNotThrow(() ->
                bridge.onProgress(AgentProgressEvent.llmContentDelta("s1", "新的流"))
        );
    }

    @Test
    @DisplayName("完整 Agent 工作流: LLM 流式 + 工具调用 + 公开消息")
    void fullWorkflowSimulation() {
        // Round 1: LLM starts streaming
        bridge.onProgress(AgentProgressEvent.llmStreamStarted("s1"));
        bridge.onProgress(AgentProgressEvent.llmContentDelta("s1", "我需要查询"));
        bridge.onProgress(AgentProgressEvent.llmContentDelta("s1", "世界信息。"));
        bridge.onProgress(AgentProgressEvent.llmStreamCompleted("s1"));

        // Tool call
        bridge.onProgress(AgentProgressEvent.toolSelected(1, 3, "query_keyword"));
        bridge.onProgress(AgentProgressEvent.toolExecuting(1, 3, "query_keyword"));
        bridge.onProgress(AgentProgressEvent.toolSuccess(1, 3, "query_keyword"));

        // Round 2: another LLM stream
        bridge.onProgress(AgentProgressEvent.llmStreamStarted("s2"));
        bridge.onProgress(AgentProgressEvent.llmStreamCompleted("s2"));

        // Public message
        bridge.onProgress(AgentProgressEvent.simulationContent("测试推文", "这是推文内容"));

        // Finish
        bridge.onProgress(AgentProgressEvent.finishAccepted(2, 3));

        // Verify nodes
        List<SessionNode> nodes = pool.getNodes("test-session");
        assertEquals(5, nodes.size()); // LLM1, TOOL, LLM2, AGENT_MSG, SYSTEM

        // Verify order by type
        List<NodeType> types = nodes.stream().map(SessionNode::type).toList();
        assertEquals(NodeType.LLM_STREAMING, types.get(0));
        assertEquals(NodeType.TOOL_CALL, types.get(1));
        assertEquals(NodeType.LLM_STREAMING, types.get(2));
        assertEquals(NodeType.AGENT_MESSAGE, types.get(3));
        assertEquals(NodeType.SYSTEM, types.get(4));
    }
}
