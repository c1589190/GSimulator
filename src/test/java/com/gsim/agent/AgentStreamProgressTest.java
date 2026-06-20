package com.gsim.agent;

import com.gsim.llm.*;
import com.gsim.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 AgentProgressEvent 流式事件 + OrchestratorAgent 流式集成。
 * 使用 FakeLlmManager 配合自定义 submit() 实现模拟流式行为。
 */
@DisplayName("Agent 流式进度事件测试")
class AgentStreamProgressTest {

    private ToolRegistry toolRegistry;
    private List<AgentProgressEvent> capturedEvents;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        capturedEvents = new CopyOnWriteArrayList<>();
    }

    // ---- 1. AgentProgressEvent factory 测试 ----

    @Test
    @DisplayName("llmStreamStarted 生成 LLM_STREAM_STARTED 事件，meta 含 streamId")
    void llmStreamStartedEvent() {
        AgentProgressEvent event = AgentProgressEvent.llmStreamStarted("s1");
        assertEquals(AgentProgressEvent.LLM_STREAM_STARTED, event.phase());
        assertEquals("s1", event.meta().get("streamId"));
    }

    @Test
    @DisplayName("llmContentDelta 生成 LLM_CONTENT_DELTA 事件，meta 含 streamId + channel=content")
    void llmContentDeltaEvent() {
        AgentProgressEvent event = AgentProgressEvent.llmContentDelta("s1", "Hello");
        assertEquals(AgentProgressEvent.LLM_CONTENT_DELTA, event.phase());
        assertEquals("Hello", event.detail());
        assertEquals("s1", event.meta().get("streamId"));
        assertEquals("content", event.meta().get("channel"));
        assertEquals("5", event.meta().get("chars"));
    }

    @Test
    @DisplayName("llmReasoningDelta 生成 LLM_REASONING_DELTA 事件，meta 含 streamId + channel=reasoning")
    void llmReasoningDeltaEvent() {
        AgentProgressEvent event = AgentProgressEvent.llmReasoningDelta("s1", "正在思考...");
        assertEquals(AgentProgressEvent.LLM_REASONING_DELTA, event.phase());
        assertEquals("正在思考...", event.detail());
        assertEquals("s1", event.meta().get("streamId"));
        assertEquals("reasoning", event.meta().get("channel"));
    }

    @Test
    @DisplayName("llmStreamCompleted 生成 LLM_STREAM_COMPLETED 事件，meta 含 streamId")
    void llmStreamCompletedEvent() {
        AgentProgressEvent event = AgentProgressEvent.llmStreamCompleted("s1");
        assertEquals(AgentProgressEvent.LLM_STREAM_COMPLETED, event.phase());
        assertEquals("s1", event.meta().get("streamId"));
    }

    @Test
    @DisplayName("llmStreamFailed 生成 LLM_STREAM_FAILED 事件，meta 含 streamId + error")
    void llmStreamFailedEvent() {
        AgentProgressEvent event = AgentProgressEvent.llmStreamFailed("s1", "网络超时");
        assertEquals(AgentProgressEvent.LLM_STREAM_FAILED, event.phase());
        assertEquals("s1", event.meta().get("streamId"));
        assertEquals("网络超时", event.meta().get("error"));
    }

    // ---- 2. OrchestratorAgent 流式集成测试 ----

    @Test
    @DisplayName("streamEnabled=true 时 OrchestratorAgent 发出 LLM_CONTENT_DELTA 和 LLM_REASONING_DELTA")
    void orchestratorEmitsContentAndReasoningDelta() throws Exception {
        AgentProgressSink sink = capturedEvents::add;

        // 创建 FakeLlmManager，重写 submit() 以模拟流式输出
        FakeLlmManager fakeLlm = new FakeLlmManager() {
            @Override
            public LlmCall submit(LlmRequest request) {
                String callId = "sim-" + UUID.randomUUID().toString().substring(0, 8);
                StreamPool pool = new StreamPool(callId);
                pool.onReasoningDelta("分析玩家行动中...");
                pool.onContentDelta("这是");
                pool.onContentDelta("测试输出");
                pool.onComplete(LlmResult.success("这是测试输出", "test", 10));
                return new LlmCall(callId, pool);
            }
        };

        OrchestratorAgent agent = new OrchestratorAgent(
                fakeLlm, toolRegistry, "test-model", sink);
        agent.setStreamEnabled(true);

        String contextMd = "# 上下文\n这是一个测试场景。";
        List<com.gsim.context.session.SessionMessage> history = List.of();
        String userText = "测试输入";

        OrchestratorAgent.ChatResult result = agent.chatWithContextSession(
                contextMd, history, userText);

        // 验证流式事件
        boolean hasStarted = capturedEvents.stream()
                .anyMatch(e -> AgentProgressEvent.LLM_STREAM_STARTED.equals(e.phase()));
        boolean hasContentDelta = capturedEvents.stream()
                .anyMatch(e -> AgentProgressEvent.LLM_CONTENT_DELTA.equals(e.phase()));
        boolean hasReasoningDelta = capturedEvents.stream()
                .anyMatch(e -> AgentProgressEvent.LLM_REASONING_DELTA.equals(e.phase()));
        boolean hasCompleted = capturedEvents.stream()
                .anyMatch(e -> AgentProgressEvent.LLM_STREAM_COMPLETED.equals(e.phase()));

        assertTrue(hasStarted, "应该有 LLM_STREAM_STARTED 事件");
        assertTrue(hasContentDelta, "应该有 LLM_CONTENT_DELTA 事件");
        assertTrue(hasReasoningDelta, "应该有 LLM_REASONING_DELTA 事件");
        assertTrue(hasCompleted, "应该有 LLM_STREAM_COMPLETED 事件");
    }

    @Test
    @DisplayName("streamEnabled=false 时 OrchestratorAgent 走原 chat 路径，不发出流式事件")
    void streamDisabledFallsBackToChat() throws Exception {
        AgentProgressSink sink = capturedEvents::add;

        // 使用标准 FakeLlmManager（走 chat 路径）
        FakeLlmManager fakeLlm = new FakeLlmManager();
        // 不设置任何响应 — chat 路径下 addResponse 会被 chat() 消费
        // 但是 OrchestratorAgent 的 ToolLoop 需要被触发...
        // 使用 addToolCallsResponse 让 finish_action 被正确消费
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_1", "finish_action",
                        Map.of("status", "success", "message", "非流式响应"))
        ));

        OrchestratorAgent agent = new OrchestratorAgent(
                fakeLlm, toolRegistry, "test-model", sink);
        agent.setStreamEnabled(false);

        String contextMd = "# 上下文\n测试。";
        List<com.gsim.context.session.SessionMessage> history = List.of();
        String userText = "测试";

        OrchestratorAgent.ChatResult result = agent.chatWithContextSession(
                contextMd, history, userText);

        // 不应该有流式事件
        boolean hasStreamEvent = capturedEvents.stream()
                .anyMatch(e -> e.phase().startsWith("LLM_STREAM"));
        assertFalse(hasStreamEvent, "streamEnabled=false 时不应有流式事件");
    }

    @Test
    @DisplayName("流式完成后 ToolLoop 仍能正确解析 API tool_calls")
    void streamCompleteToolCallsStillWork() throws Exception {
        AgentProgressSink sink = capturedEvents::add;

        FakeLlmManager fakeLlm = new FakeLlmManager() {
            @Override
            public LlmCall submit(LlmRequest request) {
                String callId = "tc-" + UUID.randomUUID().toString().substring(0, 8);
                StreamPool pool = new StreamPool(callId);
                // 模拟 tool_calls 流式（不发送 content delta）
                LlmToolCall tc = new LlmToolCall("call_1", "finish_action",
                        Map.of("message", "流式完成的回复", "status", "success"));
                pool.onToolCallDelta(0, "finish_action", tc.arguments().toString());
                pool.onComplete(LlmResult.withToolCalls(List.of(tc), "test", 5));
                return new LlmCall(callId, pool);
            }
        };

        OrchestratorAgent agent = new OrchestratorAgent(
                fakeLlm, toolRegistry, "test-model", sink);
        agent.setStreamEnabled(true);

        String contextMd = "# 上下文\n测试 tool call。";
        List<com.gsim.context.session.SessionMessage> history = List.of();
        String userText = "请结束";

        OrchestratorAgent.ChatResult result = agent.chatWithContextSession(
                contextMd, history, userText);

        assertTrue(result.success(), "流式 tool_calls 应被正确处理: " + result.errorMessage());
        assertNotNull(result.finalText());
    }

    @Test
    @DisplayName("流式异常后 LLM_STREAM_FAILED 事件被发送")
    void streamFailureSendsFailedEvent() throws Exception {
        AgentProgressSink sink = capturedEvents::add;

        FakeLlmManager failingLlm = new FakeLlmManager() {
            @Override
            public LlmCall submit(LlmRequest request) {
                String callId = "err-" + UUID.randomUUID().toString().substring(0, 8);
                StreamPool pool = new StreamPool(callId);
                pool.onError("模拟流式错误");
                return new LlmCall(callId, pool);
            }
        };

        OrchestratorAgent agent = new OrchestratorAgent(
                failingLlm, toolRegistry, "test-model", sink);
        agent.setStreamEnabled(true);

        String contextMd = "# 测试";
        List<com.gsim.context.session.SessionMessage> history = List.of();
        String userText = "hi";

        OrchestratorAgent.ChatResult result = agent.chatWithContextSession(
                contextMd, history, userText);

        boolean hasFailed = capturedEvents.stream()
                .anyMatch(e -> AgentProgressEvent.LLM_STREAM_FAILED.equals(e.phase()));
        assertTrue(hasFailed, "流式错误时应发送 LLM_STREAM_FAILED 事件");
    }

    @Test
    @DisplayName("非流式 JSON 响应 — submit 完成")
    void jsonFallbackStreamComplete() throws Exception {
        AgentProgressSink sink = capturedEvents::add;

        FakeLlmManager jsonLlm = new FakeLlmManager() {
            @Override
            public LlmCall submit(LlmRequest request) {
                String callId = "json-" + UUID.randomUUID().toString().substring(0, 8);
                StreamPool pool = new StreamPool(callId);
                pool.onContentDelta("JSON fallback 完整回答内容");
                pool.onComplete(LlmResult.success("JSON fallback 完整回答内容", "test", 10));
                return new LlmCall(callId, pool);
            }
        };

        OrchestratorAgent agent = new OrchestratorAgent(
                jsonLlm, toolRegistry, "test-model", sink);
        agent.setStreamEnabled(true);

        String contextMd = "# 上下文\n测试 JSON fallback 路径。";
        List<com.gsim.context.session.SessionMessage> history = List.of();
        String userText = "测试 JSON fallback";

        OrchestratorAgent.ChatResult result = agent.chatWithContextSession(
                contextMd, history, userText);

        boolean hasContentDelta = capturedEvents.stream()
                .anyMatch(e -> AgentProgressEvent.LLM_CONTENT_DELTA.equals(e.phase()));
        assertTrue(hasContentDelta, "JSON fallback 也应产生 content delta 事件");
    }

    @Test
    @DisplayName("streamEnabled=false 时不产生流式事件")
    void streamDisabledNoStreamEvents() throws Exception {
        AgentProgressSink sink = capturedEvents::add;

        FakeLlmManager fakeLlm = new FakeLlmManager();
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_chat", "finish_action",
                        Map.of("status", "success", "message", "非流式 chat 响应"))
        ));

        OrchestratorAgent agent = new OrchestratorAgent(
                fakeLlm, toolRegistry, "test-model", sink);
        agent.setStreamEnabled(false);

        String contextMd = "# 上下文";
        List<com.gsim.context.session.SessionMessage> history = List.of();
        String userText = "hi";

        OrchestratorAgent.ChatResult result = agent.chatWithContextSession(
                contextMd, history, userText);

        boolean hasStreamEvent = capturedEvents.stream()
                .anyMatch(e -> e.phase().startsWith("LLM_STREAM"));
        assertFalse(hasStreamEvent, "streamEnabled=false 时不应有流式事件");
    }
}
