package com.gsim.agent;

import com.gsim.llm.*;
import com.gsim.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 AgentProgressEvent 流式事件 + OrchestratorAgent 流式集成。
 * 使用 FakeLlmClient 配合自定义 stream() 实现模拟流式行为。
 */
@DisplayName("Agent 流式进度事件测试")
class AgentStreamProgressTest {

    private ToolRegistry toolRegistry;
    private List<AgentProgressEvent> capturedEvents;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
        // 注册 finish_action 和 console_print（ToolLoop 需要）
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

        // 创建 FakeLlmClient，重写 stream() 以模拟流式输出
        FakeLlmClient fakeLlm = new FakeLlmClient();
        LlmClient streamingClient = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                return fakeLlm.chat(request);
            }

            @Override
            public void stream(LlmRequest request, LlmStreamListener listener) {
                listener.onStart();
                listener.onReasoningDelta("分析玩家行动中...");
                listener.onContentDelta("这是");
                listener.onContentDelta("测试输出");
                // 组装最终响应
                if (listener instanceof LlmStreamCollector collector) {
                    collector.setFinalResponse(LlmResponse.success("这是测试输出", "test", 10));
                    collector.setReasoning("分析玩家行动中...");
                }
                listener.onComplete();
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        OrchestratorAgent agent = new OrchestratorAgent(
                streamingClient, toolRegistry, "test-model", sink);
        agent.setStreamEnabled(true);

        // 使用 chatWithContextSession 触发 LLM 调用
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

        FakeLlmClient fakeLlm = new FakeLlmClient();
        // 模拟流式（但 streamEnabled=false 时不会调用）
        LlmClient client = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                return LlmResponse.success("非流式响应", "test", 5);
            }

            @Override
            public void stream(LlmRequest request, LlmStreamListener listener) {
                // 不应该被调用
                fail("streamEnabled=false 时不应调用 stream()");
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        OrchestratorAgent agent = new OrchestratorAgent(
                client, toolRegistry, "test-model", sink);
        agent.setStreamEnabled(false); // 显式关闭

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

        FakeLlmClient fakeLlm = new FakeLlmClient();
        LlmClient streamingClient = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                return fakeLlm.chat(request);
            }

            @Override
            public void stream(LlmRequest request, LlmStreamListener listener) {
                listener.onStart();
                // 模拟 tool_calls 流式（不发送 content delta）
                if (listener instanceof LlmStreamCollector collector) {
                    collector.setToolCalls(List.of(
                            new LlmToolCall("call_1", "finish_action",
                                    Map.of("message", "流式完成的回复",
                                           "status", "success"))
                    ));
                    collector.setFinalResponse(LlmResponse.successWithToolCalls(
                            List.of(new LlmToolCall("call_1", "finish_action",
                                    Map.of("message", "流式完成的回复",
                                           "status", "success"))),
                            "test", 5));
                }
                listener.onToolCallDelta("tool:finish_action");
                listener.onComplete();
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        OrchestratorAgent agent = new OrchestratorAgent(
                streamingClient, toolRegistry, "test-model", sink);
        agent.setStreamEnabled(true);

        String contextMd = "# 上下文\n测试 tool call。";
        List<com.gsim.context.session.SessionMessage> history = List.of();
        String userText = "请结束";

        OrchestratorAgent.ChatResult result = agent.chatWithContextSession(
                contextMd, history, userText);

        // 验证 ToolLoop 正确处理了 finish_action
        assertTrue(result.success(), "流式 tool_calls 应被正确处理");
        assertNotNull(result.finalText());
    }

    @Test
    @DisplayName("流式异常后 LLM_STREAM_FAILED 事件被发送")
    void streamFailureSendsFailedEvent() throws Exception {
        AgentProgressSink sink = capturedEvents::add;

        LlmClient failingClient = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                return LlmResponse.failure("chat error");
            }

            @Override
            public void stream(LlmRequest request, LlmStreamListener listener) {
                listener.onStart();
                listener.onError(new RuntimeException("模拟流式错误"));
                listener.onComplete();
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        OrchestratorAgent agent = new OrchestratorAgent(
                failingClient, toolRegistry, "test-model", sink);
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

    // ---- 3. JSON fallback → registry 集成测试 ----

    @Test
    @DisplayName("JSON fallback 也更新 registry：非流式 JSON 响应 → registry snapshot 含内容")
    void jsonFallbackUpdatesRegistry() throws Exception {
        AgentProgressSink sink = capturedEvents::add;

        // 模拟不支持 SSE 的 API — stream() 内部走 JSON fallback
        LlmClient jsonFallbackClient = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                return LlmResponse.success("直接 chat 响应", "test", 5);
            }

            @Override
            public void stream(LlmRequest request, LlmStreamListener listener) {
                // 模拟 JSON fallback 行为：发射完整 content delta 后 complete
                listener.onStart();
                listener.onContentDelta("JSON fallback 完整回答内容");
                if (listener instanceof LlmStreamCollector collector) {
                    collector.setFinalResponse(
                            LlmResponse.success("JSON fallback 完整回答内容", "test", 10));
                }
                listener.onComplete();
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        LlmStreamStateRegistry registry = new LlmStreamStateRegistry();
        OrchestratorAgent agent = new OrchestratorAgent(
                jsonFallbackClient, toolRegistry, "test-model", sink);
        agent.setStreamRegistry(registry);
        agent.setStreamEnabled(true);

        String contextMd = "# 上下文\n测试 JSON fallback 路径。";
        List<com.gsim.context.session.SessionMessage> history = List.of();
        String userText = "测试 JSON fallback";

        OrchestratorAgent.ChatResult result = agent.chatWithContextSession(
                contextMd, history, userText);

        // 验证 registry 被更新（而非只更新了内部 collector）
        // 注意：stream() 完成后 callLlm 调用 registry.complete()，
        // 所以 activeCount 可能为 0，但 snapshot 仍在 registry 中

        // 通过事件中的 streamId 查找 registry snapshot
        boolean foundContent = false;
        for (AgentProgressEvent event : capturedEvents) {
            if (event.phase().equals(AgentProgressEvent.LLM_CONTENT_DELTA)) {
                String streamId = event.meta().get("streamId");
                LlmStreamSnapshot snap = registry.snapshot(streamId);
                if (snap != null && snap != LlmStreamSnapshot.EMPTY
                        && !snap.content().isEmpty()) {
                    foundContent = true;
                    break;
                }
            }
        }
        assertTrue(foundContent,
                "registry 中应有包含内容的 snapshot（JSON fallback 也走 registry）");
    }

    @Test
    @DisplayName("streamEnabled=false 时不更新 registry（走 chat 路径）")
    void streamDisabledDoesNotUpdateRegistry() throws Exception {
        AgentProgressSink sink = capturedEvents::add;

        LlmClient chatClient = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                return LlmResponse.success("非流式 chat 响应", "test", 5);
            }

            @Override
            public void stream(LlmRequest request, LlmStreamListener listener) {
                fail("streamEnabled=false 时不应调用 stream()");
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        LlmStreamStateRegistry registry = new LlmStreamStateRegistry();
        OrchestratorAgent agent = new OrchestratorAgent(
                chatClient, toolRegistry, "test-model", sink);
        agent.setStreamRegistry(registry);
        agent.setStreamEnabled(false);

        String contextMd = "# 上下文";
        List<com.gsim.context.session.SessionMessage> history = List.of();
        String userText = "hi";

        OrchestratorAgent.ChatResult result = agent.chatWithContextSession(
                contextMd, history, userText);

        // 非流式路径不应在 registry 中创建任何 stream
        assertEquals(0, registry.activeCount(),
                "streamEnabled=false 时 registry 应无活跃 stream");
    }
}
