package com.gsim.llm;

import com.gsim.agent.*;
import com.gsim.app.AppConfig;
import com.gsim.config.ConfigLoader;
import com.gsim.tool.ToolRegistry;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 OpenAiCompatibleLlmClient 的流式相关行为（不依赖真实网络）。
 * 因为 stream() 需要真实 HTTP，这里重点测试配置和 listener 基础设施。
 */
@DisplayName("LLM 流式基础设施测试")
class OpenAiCompatibleStreamTest {

    private AppConfig config;

    @BeforeEach
    void setUp() {
        config = AppConfig.forTesting();
    }

    // ---- 1. 配置测试 ----

    @Test
    @DisplayName("llm.stream.enabled 默认值为 true")
    void streamEnabledDefaultsToTrue() {
        assertTrue(config.isLlmStreamEnabled(),
                "llm.stream.enabled 应该默认为 true");
    }

    @Test
    @DisplayName("cli.stream.preview.enabled 默认值为 true")
    void cliStreamPreviewEnabledDefaultsToTrue() {
        assertTrue(config.isCliStreamPreviewEnabled(),
                "cli.stream.preview.enabled 应该默认为 true");
    }

    @Test
    @DisplayName("cli.stream.preview.max_chars 默认值为 3000")
    void cliStreamPreviewMaxCharsDefaultsTo3000() {
        assertEquals(3000, config.getCliStreamPreviewMaxChars(),
                "cli.stream.preview.max_chars 应该默认为 3000");
    }

    @Test
    @DisplayName("cli.stream.preview.show_reasoning 默认值为 true")
    void cliStreamPreviewShowReasoningDefaultsToTrue() {
        assertTrue(config.isCliStreamPreviewShowReasoning(),
                "cli.stream.preview.show_reasoning 应该默认为 true");
    }

    // ---- 2. DefaultLlmStreamCollector 测试 ----

    @Test
    @DisplayName("DefaultLlmStreamCollector 收集 content delta")
    void collectorCollectsContentDelta() {
        DefaultLlmStreamCollector collector = new DefaultLlmStreamCollector();
        collector.onContentDelta("Hello ");
        collector.onContentDelta("World");
        assertEquals("Hello World", collector.getFullContent());
    }

    @Test
    @DisplayName("DefaultLlmStreamCollector 收集 reasoning delta")
    void collectorCollectsReasoningDelta() {
        DefaultLlmStreamCollector collector = new DefaultLlmStreamCollector();
        collector.onReasoningDelta("Let me think...");
        assertEquals("Let me think...", collector.getReasoning());
    }

    @Test
    @DisplayName("DefaultLlmStreamCollector setFinalResponse 和 getFinalResponse")
    void collectorFinalResponseRoundtrip() {
        DefaultLlmStreamCollector collector = new DefaultLlmStreamCollector();
        LlmResponse expected = LlmResponse.success("Hello", "test-model", 10);
        collector.setFinalResponse(expected);
        assertEquals(expected, collector.getFinalResponse());
    }

    @Test
    @DisplayName("DefaultLlmStreamCollector setToolCalls 和 getToolCalls")
    void collectorToolCallsRoundtrip() {
        DefaultLlmStreamCollector collector = new DefaultLlmStreamCollector();
        List<LlmToolCall> toolCalls = List.of(
                new LlmToolCall("call_1", "finish_action",
                        java.util.Map.of("message", "done"))
        );
        collector.setToolCalls(toolCalls);
        assertEquals(1, collector.getToolCalls().size());
        assertEquals("finish_action", collector.getToolCalls().get(0).name());
    }

    // ---- 3. LlmStreamListener 模拟流式 delta 测试 ----

    @Test
    @DisplayName("模拟 content delta 流式 → listener 收到正文 token")
    void simulatedStreamParsesContentDelta() throws Exception {
        List<String> receivedContent = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        LlmStreamListener listener = new LlmStreamListener() {
            @Override
            public void onContentDelta(String text) {
                receivedContent.add(text);
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        };

        // 模拟流式 content delta
        listener.onStart();
        listener.onContentDelta("Hello ");
        listener.onContentDelta("World");
        listener.onComplete();

        assertTrue(done.await(1, TimeUnit.SECONDS));
        assertEquals(2, receivedContent.size());
        assertEquals("Hello ", receivedContent.get(0));
        assertEquals("World", receivedContent.get(1));
    }

    @Test
    @DisplayName("模拟 reasoning delta 流式 → listener 收到 reasoning token")
    void simulatedStreamParsesReasoningDelta() throws Exception {
        List<String> receivedReasoning = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        LlmStreamListener listener = new LlmStreamListener() {
            @Override
            public void onContentDelta(String text) {
                // ignore content
            }

            @Override
            public void onReasoningDelta(String text) {
                receivedReasoning.add(text);
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        };

        listener.onStart();
        listener.onReasoningDelta("正在分析");
        listener.onReasoningDelta("玩家行动...");
        listener.onComplete();

        assertTrue(done.await(1, TimeUnit.SECONDS));
        assertEquals(2, receivedReasoning.size());
        assertEquals("正在分析", receivedReasoning.get(0));
        assertEquals("玩家行动...", receivedReasoning.get(1));
    }

    @Test
    @DisplayName("模拟 [DONE] 后 listener complete")
    void simulatedStreamDoneAfterComplete() throws Exception {
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        LlmStreamListener listener = new LlmStreamListener() {
            @Override
            public void onContentDelta(String text) {
            }

            @Override
            public void onComplete() {
                completed.set(true);
                done.countDown();
            }
        };

        listener.onStart();
        listener.onContentDelta("test");
        listener.onComplete();

        assertTrue(done.await(1, TimeUnit.SECONDS));
        assertTrue(completed.get(), "[DONE] 后应调用 onComplete");
    }

    @Test
    @DisplayName("tool_calls delta 不产生 content 或 reasoning preview")
    void toolCallDeltaNotShownAsContentOrReasoning() throws Exception {
        AtomicInteger contentCount = new AtomicInteger(0);
        AtomicInteger reasoningCount = new AtomicInteger(0);
        AtomicInteger toolCallCount = new AtomicInteger(0);

        LlmStreamListener listener = new LlmStreamListener() {
            @Override
            public void onContentDelta(String text) {
                contentCount.incrementAndGet();
            }

            @Override
            public void onReasoningDelta(String text) {
                reasoningCount.incrementAndGet();
            }

            @Override
            public void onToolCallDelta(String text) {
                toolCallCount.incrementAndGet();
            }

            @Override
            public void onComplete() {
            }
        };

        // 模拟 tool_call delta — 不应该触发 content/reasoning
        listener.onToolCallDelta("tool:finish_action");
        listener.onToolCallDelta("tool:knowledge_search");

        assertEquals(0, contentCount.get(), "tool_call 不应触发 onContentDelta");
        assertEquals(0, reasoningCount.get(), "tool_call 不应触发 onReasoningDelta");
        assertEquals(2, toolCallCount.get(), "tool_call 应触发 onToolCallDelta");
    }

    // ---- 4. 错误处理 ----

    @Test
    @DisplayName("listener onError 被调用时正确传递异常")
    void listenerOnErrorCalledWithException() throws Exception {
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        AtomicInteger errorCount = new AtomicInteger(0);

        LlmStreamListener listener = new LlmStreamListener() {
            @Override
            public void onContentDelta(String text) {
            }

            @Override
            public void onError(Throwable error) {
                errorReceived.set(true);
                errorCount.incrementAndGet();
            }

            @Override
            public void onComplete() {
            }
        };

        listener.onStart();
        listener.onError(new RuntimeException("Stream error"));
        listener.onComplete();

        assertTrue(errorReceived.get(), "应该收到 onError");
        assertEquals(1, errorCount.get());
    }

    // ---- 5. LlmClient 默认 stream() 降级测试 ----

    @Test
    @DisplayName("LlmClient 默认 stream() 退化到 chat()")
    void defaultStreamFallsBackToChat() throws Exception {
        // 创建一个匿名 LlmClient，只实现 chat()
        AtomicBoolean chatCalled = new AtomicBoolean(false);
        LlmClient client = new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                chatCalled.set(true);
                return LlmResponse.success("fallback response", "test", 5);
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        List<String> contentDeltas = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        LlmStreamListener listener = new LlmStreamListener() {
            @Override
            public void onContentDelta(String text) {
                contentDeltas.add(text);
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        };

        LlmRequest req = new LlmRequest("test", List.of(), 0.3, 100);
        client.stream(req, listener);

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(chatCalled.get(), "默认 stream() 应该调用 chat()");
        assertEquals(1, contentDeltas.size());
        assertEquals("fallback response", contentDeltas.get(0));
    }

    // ======================== 6. 真实 SSE 解析测试 ========================

    /**
     * 构造一个模拟 DeepSeek SSE 响应的 ResponseBody，
     * 调用 processSseStream，验证 delta 回调。
     */
    private static ResponseBody sseBody(String... lines) {
        String content = String.join("\n", lines);
        return ResponseBody.create(content, MediaType.get("text/event-stream"));
    }

    private static ResponseBody jsonBody(String json) {
        return ResponseBody.create(json, MediaType.get("application/json"));
    }

    @Test
    @DisplayName("DeepSeek SSE content delta: 真实 SSE bytes → onContentDelta 逐 token 触发")
    void deepseekSseContentDeltaTest() throws Exception {
        // 模拟 DeepSeek 标准 SSE 响应
        ResponseBody body = sseBody(
                "data: {\"id\":\"chatcmpl-001\",\"object\":\"chat.completion.chunk\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"阿道芙\"},\"finish_reason\":null}]}",
                "",
                "data: {\"id\":\"chatcmpl-001\",\"object\":\"chat.completion.chunk\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"进入学校\"},\"finish_reason\":null}]}",
                "",
                "data: {\"id\":\"chatcmpl-001\",\"object\":\"chat.completion.chunk\","
                        + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "",
                "data: [DONE]"
        );

        AppConfig config = AppConfig.forTesting();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        DefaultLlmStreamCollector collector = new DefaultLlmStreamCollector();

        List<String> deltas = new ArrayList<>();
        LlmStreamCollector listener = new LlmStreamCollector() {
            @Override public void onStart() {}
            @Override public void onContentDelta(String text) { deltas.add(text); collector.onContentDelta(text); }
            @Override public void onReasoningDelta(String text) { collector.onReasoningDelta(text); }
            @Override public void onToolCallDelta(String text) { collector.onToolCallDelta(text); }
            @Override public void onError(Throwable error) {}
            @Override public void onComplete() {}
            @Override public void setFinalResponse(LlmResponse r) { collector.setFinalResponse(r); }
            @Override public LlmResponse getFinalResponse() { return collector.getFinalResponse(); }
            @Override public void setReasoning(String r) { collector.setReasoning(r); }
            @Override public String getReasoning() { return collector.getReasoning(); }
            @Override public void setToolCalls(List<LlmToolCall> tcs) { collector.setToolCalls(tcs); }
            @Override public List<LlmToolCall> getToolCalls() { return collector.getToolCalls(); }
            @Override public String getFullContent() { return collector.getFullContent(); }
        };

        client.processSseStream(body, listener);

        assertEquals(2, deltas.size(), "应收到 2 次 content delta");
        assertEquals("阿道芙", deltas.get(0));
        assertEquals("进入学校", deltas.get(1));
        assertEquals("阿道芙进入学校", collector.getFullContent());
    }

    @Test
    @DisplayName("JSON fallback: 非 SSE 普通 JSON → 至少 emit 一次完整 content delta")
    void jsonFallbackEmitsContentDelta() throws Exception {
        // 模拟 API 在 stream:true 下仍返回普通 JSON（如讯飞）
        String json = """
                {
                  "id": "chatcmpl-001",
                  "object": "chat.completion",
                  "model": "astron-code-latest",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "完整回答：阿道芙进入了学校大门。"
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }""";
        ResponseBody body = jsonBody(json);

        AppConfig config = AppConfig.forTesting();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        DefaultLlmStreamCollector collector = new DefaultLlmStreamCollector();

        List<String> deltas = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);

        LlmStreamCollector listener = new LlmStreamCollector() {
            @Override public void onStart() {}
            @Override public void onContentDelta(String text) { deltas.add(text); collector.onContentDelta(text); }
            @Override public void onReasoningDelta(String text) { collector.onReasoningDelta(text); }
            @Override public void onToolCallDelta(String text) {}
            @Override public void onError(Throwable error) {}
            @Override public void onComplete() { completed.countDown(); }
            @Override public void setFinalResponse(LlmResponse r) { collector.setFinalResponse(r); }
            @Override public LlmResponse getFinalResponse() { return collector.getFinalResponse(); }
            @Override public void setReasoning(String r) { collector.setReasoning(r); }
            @Override public String getReasoning() { return collector.getReasoning(); }
            @Override public void setToolCalls(List<LlmToolCall> tcs) { collector.setToolCalls(tcs); }
            @Override public List<LlmToolCall> getToolCalls() { return collector.getToolCalls(); }
            @Override public String getFullContent() { return collector.getFullContent(); }
        };

        client.processSseStream(body, listener);
        assertTrue(completed.await(1, TimeUnit.SECONDS));

        assertFalse(deltas.isEmpty(),
                "JSON fallback 应至少 emit 一次 content delta，灰框不能永远只有等待");
        assertEquals("完整回答：阿道芙进入了学校大门。", deltas.get(0));
        assertEquals("完整回答：阿道芙进入了学校大门。", collector.getFullContent());
    }

    @Test
    @DisplayName("reasoning delta: SSE 中的 reasoning_content → onReasoningDelta 触发")
    void reasoningDeltaTest() throws Exception {
        ResponseBody body = sseBody(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"正在分析玩家行动背景\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"结论：行动有效\"}}]}",
                "",
                "data: [DONE]"
        );

        AppConfig config = AppConfig.forTesting();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        DefaultLlmStreamCollector collector = new DefaultLlmStreamCollector();

        List<String> reasoningDeltas = new ArrayList<>();
        List<String> contentDeltas = new ArrayList<>();

        LlmStreamCollector listener = new LlmStreamCollector() {
            @Override public void onStart() {}
            @Override public void onContentDelta(String text) { contentDeltas.add(text); collector.onContentDelta(text); }
            @Override public void onReasoningDelta(String text) { reasoningDeltas.add(text); collector.onReasoningDelta(text); }
            @Override public void onToolCallDelta(String text) {}
            @Override public void onError(Throwable error) {}
            @Override public void onComplete() {}
            @Override public void setFinalResponse(LlmResponse r) { collector.setFinalResponse(r); }
            @Override public LlmResponse getFinalResponse() { return collector.getFinalResponse(); }
            @Override public void setReasoning(String r) { collector.setReasoning(r); }
            @Override public String getReasoning() { return collector.getReasoning(); }
            @Override public void setToolCalls(List<LlmToolCall> tcs) { collector.setToolCalls(tcs); }
            @Override public List<LlmToolCall> getToolCalls() { return collector.getToolCalls(); }
            @Override public String getFullContent() { return collector.getFullContent(); }
        };

        client.processSseStream(body, listener);

        assertEquals(1, reasoningDeltas.size(), "应收到 1 次 reasoning delta");
        assertEquals("正在分析玩家行动背景", reasoningDeltas.get(0));
        assertEquals(1, contentDeltas.size(), "应收到 1 次 content delta");
        assertEquals("结论：行动有效", contentDeltas.get(0));
    }

    // ======================== 7. ToolLoop + stream 集成测试 ========================

    /**
     * 创建一个 FakeLlmClient，通过回调控制 stream 行为。
     */
    private static class ControlledStreamClient implements LlmClient {
        private LlmResponse chatResponse = LlmResponse.success("", "test", 0);
        private volatile Runnable streamHook = null; // 在 stream() 中注入 delta

        void setChatResponse(LlmResponse r) { this.chatResponse = r; }
        void setStreamHook(Runnable hook) { this.streamHook = hook; }

        @Override
        public LlmResponse chat(LlmRequest request) {
            return chatResponse;
        }

        @Override
        public void stream(LlmRequest request, LlmStreamListener listener) {
            listener.onStart();
            if (streamHook != null) {
                streamHook.run();
            }
            // 默认：发射 content delta 然后 complete
            if (chatResponse.content() != null && !chatResponse.content().isEmpty()) {
                listener.onContentDelta(chatResponse.content());
            }
            if (listener instanceof LlmStreamCollector c) {
                c.setFinalResponse(chatResponse);
                if (chatResponse.hasApiToolCalls()) {
                    c.setToolCalls(chatResponse.toolCalls());
                }
            }
            listener.onComplete();
        }

        @Override
        public boolean isAvailable() { return true; }
    }

    @Test
    @DisplayName("no-finish-action stream reprompt: R1 普通文本被拒 → R2 调用 finish_action 成功")
    void noFinishActionStreamRepromptTest() {
        ControlledStreamClient llmClient = new ControlledStreamClient();
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());

        var recorder = new java.util.concurrent.CopyOnWriteArrayList<String>();
        AgentProgressSink sink = event -> {
            if (event != null) recorder.add(event.phase());
        };

        OrchestratorAgent agent = new OrchestratorAgent(llmClient, toolRegistry, "test-model", sink);
        agent.setMaxToolRounds(8);
        agent.setStreamEnabled(true);

        // R1: 返回普通文本（无 finish_action）
        AtomicInteger callCount = new AtomicInteger(0);
        llmClient.setChatResponse(LlmResponse.success("这是完整的报名表内容……", "test", 0));
        llmClient.setStreamHook(() -> callCount.incrementAndGet());

        // R1 返回普通文本 → 应该被拒绝
        // 用 runToolLoop 的逻辑：通过 chatWithContextSession 测试
        List<com.gsim.context.session.SessionMessage> sessionMessages = List.of();
        OrchestratorAgent.ChatResult result = agent.chatWithContextSession(
                "base context", sessionMessages, "帮我生成报名表");

        // 断言：R1 普通文本没有被当作最终输出
        assertFalse(result.success(),
                "R1 普通文本无 finish_action 不应成功");
        assertNotNull(result.errorMessage(), "应有明确错误消息");
        assertTrue(result.errorMessage().contains("没有调用任何工具")
                        || result.errorMessage().contains("no tool calls")
                        || result.errorMessage().contains("Agent"),
                "错误消息应说明 ToolLoop 失败原因，实际: " + result.errorMessage());
    }

    @Test
    @DisplayName("repeated no-finish-action: 连续两轮无 finish_action → 不静默 out，有明确错误")
    void repeatedNoFinishActionDoesNotSilentlyOut() {
        ControlledStreamClient llmClient = new ControlledStreamClient();
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());

        var recorder = new java.util.concurrent.CopyOnWriteArrayList<String>();
        AgentProgressSink sink = event -> {
            if (event != null) recorder.add(event.phase());
        };

        OrchestratorAgent agent = new OrchestratorAgent(llmClient, toolRegistry, "test-model", sink);
        agent.setMaxToolRounds(8);
        agent.setStreamEnabled(true);

        // 始终返回普通文本（没有 tool_calls）
        llmClient.setChatResponse(LlmResponse.success("一段无工具调用的普通文本", "test", 0));

        List<com.gsim.context.session.SessionMessage> sessionMessages = List.of();
        OrchestratorAgent.ChatResult result = agent.chatWithContextSession(
                "base context", sessionMessages, "测试请求");

        // 断言：不应静默成功
        assertFalse(result.success(),
                "连续无 finish_action 不应静默 out，应返回失败");
        assertNotNull(result.errorMessage(), "必须有明确错误消息");
        assertFalse(result.errorMessage().isBlank(), "错误消息不能为空");
        // 应该包含"Agent produced no tool calls"或类似表述
        assertTrue(result.errorMessage().contains("没有调用任何工具")
                        || result.errorMessage().contains("no tool calls")
                        || result.errorMessage().contains("finish_action"),
                "错误消息应说明原因，实际: " + result.errorMessage());
    }
}
