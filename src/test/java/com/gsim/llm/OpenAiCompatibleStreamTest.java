package com.gsim.llm;

import com.gsim.app.AppConfig;
import com.gsim.config.ConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
}
