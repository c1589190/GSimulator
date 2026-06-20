package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmManager;
import com.gsim.llm.LlmCall;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.LlmResult;
import com.gsim.llm.StreamPool;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证流式 inline 打印行为 + submit() → StreamPool 的各种最终内容组装场景。
 */
@DisplayName("流式 inline 打印 + STREAM_TRACE")
class CliStreamPreviewAndTraceTest {

    private ByteArrayOutputStream outContent;
    private PrintStream ps;

    @BeforeEach
    void setUp() {
        outContent = new ByteArrayOutputStream();
        ps = new PrintStream(outContent, true);
    }

    // ===== inline 打印测试 =====

    @Test
    @DisplayName("LLM STREAM 事件通过 sink 直接 inline 打印，不依赖 renderer")
    void sinkDirectlyPrintsStreamEvents() {
        var sink = new CliAgentProgressSink(ps, true);

        sink.onProgress(AgentProgressEvent.llmStreamStarted("s1"));
        assertTrue(outContent.toString().contains("[...]"),
                "STARTED 应输出 [...] 前缀");

        sink.onProgress(AgentProgressEvent.llmContentDelta("s1", "hello"));
        assertTrue(outContent.toString().contains("hello"),
                "CONTENT_DELTA 应直接打印 delta");

        sink.onProgress(AgentProgressEvent.llmStreamCompleted("s1"));
        // COMPLETED 追加换行 — 只验证不抛异常
    }

    @Test
    @DisplayName("LLM_STREAM_FAILED 通过 sink 打印错误")
    void sinkPrintsErrorOnStreamFailed() {
        var sink = new CliAgentProgressSink(ps, true);

        sink.onProgress(AgentProgressEvent.llmStreamFailed("test-fail", "连接超时"));

        String output = outContent.toString();
        assertTrue(output.contains("连接超时"),
                "应包含错误详情，实际: " + output);
    }

    @Test
    @DisplayName("非流式事件正常格式化，不依赖于灰框清除")
    void nonStreamEventsPrintNormally() {
        var sink = new CliAgentProgressSink(ps, true);

        var event = AgentProgressEvent.waitingLlm(1, 10);
        sink.onProgress(event);

        String output = outContent.toString();
        assertTrue(output.contains("[Agent]"),
                "非流式事件应格式化 [Agent] 行: " + output);
    }

    @Test
    @DisplayName("非流式事件 TOOL_SELECTED 正常格式化")
    void toolSelectedLinePrintsNormally() {
        var sink = new CliAgentProgressSink(ps, true);

        var event = AgentProgressEvent.toolSelected(3, 10, "query_knowledge");
        sink.onProgress(event);

        String output = outContent.toString();
        assertTrue(output.contains("query_knowledge"),
                "应有 toolSelected 行包含工具名");
    }

    // ===== 流式内容组装测试 =====

    @Test
    @DisplayName("submit 产生 0 个 content delta 但最终有 content → STREAM_TRACE 正确对照")
    void streamTraceReportsZeroDeltaButFinalContent() {
        var noDeltaLlm = new FakeLlmManager() {
            @Override
            public LlmCall submit(LlmRequest request) {
                String callId = "fake-" + UUID.randomUUID().toString().substring(0, 8);
                StreamPool pool = new StreamPool(callId);
                pool.onComplete(LlmResult.success("最终内容", "test-model", 5));
                return new LlmCall(callId, pool);
            }
        };

        var tools = new ToolRegistry();
        tools.register(new FinishActionTool());

        var agent = new OrchestratorAgent(noDeltaLlm, tools, "test-model");
        agent.setStreamEnabled(true);

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询状态");

        assertTrue(result.success(),
                "stream 无 delta 但最终有 content → auto-wrap 使用最终内容: " + result.errorMessage());
        assertEquals("最终内容", result.finalText());
    }

    @Test
    @DisplayName("submit 产生 0 个 content delta + 空最终 content → pendingPlainContent 兜底")
    void streamTraceReportsZeroDeltaZeroContent() {
        var customLlm = new FakeLlmManager() {
            private int submitCallCount = 0;

            @Override
            public LlmCall submit(LlmRequest request) {
                submitCallCount++;
                String callId = "fake-" + UUID.randomUUID().toString().substring(0, 8);
                StreamPool pool = new StreamPool(callId);

                if (submitCallCount == 1) {
                    LlmResult r = chat(request);
                    if (r.success() && r.content() != null) {
                        pool.onContentDelta(r.content());
                    }
                    pool.onComplete(r);
                    return new LlmCall(callId, pool);
                }
                pool.onComplete(LlmResult.success("", "test-model", 0));
                return new LlmCall(callId, pool);
            }
        };

        var tools = new ToolRegistry();
        tools.register(new FinishActionTool());

        var agent = new OrchestratorAgent(customLlm, tools, "test-model");
        agent.setStreamEnabled(true);

        customLlm.addResponse("说话");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "说话");

        assertTrue(result.success(),
                "R2 0 delta 0 content → 应用 R1 pendingPlainContent 兜底: " + result.errorMessage());
        assertEquals("说话", result.finalText(),
                "finalText 应为 R1 pendingPlainContent");
    }

    @Test
    @DisplayName("submit 0 delta + 0 content + 无 pendingPlainContent → 提前中止")
    void streamZeroDeltaZeroContentNoPendingAbort() {
        var noContentLlm = new FakeLlmManager() {
            @Override
            public LlmCall submit(LlmRequest request) {
                String callId = "fake-" + UUID.randomUUID().toString().substring(0, 8);
                StreamPool pool = new StreamPool(callId);
                pool.onComplete(LlmResult.success("", "test-model", 0));
                return new LlmCall(callId, pool);
            }
        };

        var tools = new ToolRegistry();
        tools.register(new FinishActionTool());

        var agent = new OrchestratorAgent(noContentLlm, tools, "test-model");
        agent.setStreamEnabled(true);

        noContentLlm.addResponse("");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertFalse(result.success(), "两轮无内容应 abort");
        assertTrue(result.errorMessage() != null
                        && result.errorMessage().contains("consecutive"),
                "应触发 consecutive no-tool abort: " + result.errorMessage());
    }

    @Test
    @DisplayName("submit 有 deltas + 有 finalContent → 正确组装")
    void streamTraceFullDeltasAndContent() {
        var normalStreamLlm = new FakeLlmManager() {
            @Override
            public LlmCall submit(LlmRequest request) {
                String callId = "fake-" + UUID.randomUUID().toString().substring(0, 8);
                StreamPool pool = new StreamPool(callId);
                pool.onContentDelta("部分内容");
                pool.onComplete(LlmResult.success("部分内容", "test-model", 10));
                return new LlmCall(callId, pool);
            }
        };

        var tools = new ToolRegistry();
        tools.register(new FinishActionTool());

        var agent = new OrchestratorAgent(normalStreamLlm, tools, "test-model");
        agent.setStreamEnabled(true);

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询");

        assertTrue(result.success(),
                "stream 有 delta 有 content → auto-wrap 成功: " + result.errorMessage());
        assertEquals("部分内容", result.finalText());
    }

    @Test
    @DisplayName("submit fallback 组装 content → 匹配 ToolLoop 看到的 content")
    void streamTraceFinalContentMatchesToolLoopContent() {
        var fallbackLlm = new FakeLlmManager() {
            @Override
            public LlmCall submit(LlmRequest request) {
                String callId = "fake-" + UUID.randomUUID().toString().substring(0, 8);
                StreamPool pool = new StreamPool(callId);
                pool.onContentDelta("真实回复内容");
                pool.onComplete(LlmResult.success("真实回复内容", "test-model", 10));
                return new LlmCall(callId, pool);
            }
        };

        var tools = new ToolRegistry();
        tools.register(new FinishActionTool());

        var agent = new OrchestratorAgent(fallbackLlm, tools, "test-model");
        agent.setStreamEnabled(true);

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "说话");

        assertTrue(result.success(),
                "fallback 组装 content → auto-wrap 成功: " + result.errorMessage());
        assertEquals("真实回复内容", result.finalText(),
                "finalText 应匹配 delta 内容");
    }

    @Test
    @DisplayName("submit 空内容 + 空池 → 提前中止")
    void streamTraceSourceFallbackEmpty() {
        var emptyLlm = new FakeLlmManager() {
            private int callCount = 0;
            @Override
            public LlmCall submit(LlmRequest request) {
                callCount++;
                String callId = "fake-" + UUID.randomUUID().toString().substring(0, 8);
                StreamPool pool = new StreamPool(callId);
                pool.onComplete(LlmResult.success("", "test-model", 0));
                return new LlmCall(callId, pool);
            }
            @Override
            public LlmResult chat(LlmRequest request) {
                return LlmResult.success("", "test-model", 0);
            }
        };

        var tools = new ToolRegistry();
        tools.register(new FinishActionTool());

        var agent = new OrchestratorAgent(emptyLlm, tools, "test-model");
        agent.setStreamEnabled(true);

        emptyLlm.addResponse("");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertFalse(result.success());
    }
}
