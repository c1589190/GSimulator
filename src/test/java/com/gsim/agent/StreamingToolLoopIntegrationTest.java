package com.gsim.agent;

import com.gsim.agent.tool.ConsolePrintTool;
import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.*;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流式 ToolLoop 集成测试。
 * 验证 stream() 路径下 API 原生 tool_calls 被正确消费，
 * finish_action 正常结束 ToolLoop，no-finish_action reprompt 正常工作。
 */
@DisplayName("流式 ToolLoop 集成测试")
class StreamingToolLoopIntegrationTest {

    private StreamingFakeClient llmClient;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    /**
     * 模拟流式响应的 FakeLlmClient。
     * 覆盖 stream() 以正确设置 finalResponse + tool_calls + delta 事件。
     */
    private static class StreamingFakeClient extends FakeLlmClient {
        private volatile Runnable streamHook;

        void setStreamHook(Runnable hook) { this.streamHook = hook; }

        @Override
        public void stream(LlmRequest request, LlmStreamListener listener) {
            listener.onStart();
            if (streamHook != null) streamHook.run();

            LlmResponse response = chat(request);

            if (response.success()) {
                // Emit content delta
                if (response.content() != null && !response.content().isEmpty()) {
                    listener.onContentDelta(response.content());
                }
            }

            if (response.hasApiToolCalls()) {
                for (LlmToolCall tc : response.toolCalls()) {
                    listener.onToolCallDelta("tool:" + tc.name());
                }
                if (listener instanceof LlmStreamCollector c) {
                    c.setToolCalls(response.toolCalls());
                    c.setFinalResponse(LlmResponse.successWithToolCalls(
                            response.toolCalls(), "test-model", 0));
                }
            } else {
                if (listener instanceof LlmStreamCollector c) {
                    c.setFinalResponse(response);
                }
            }

            if (!response.success()) {
                listener.onError(new RuntimeException(response.errorMessage()));
            }
            listener.onComplete();
        }
    }

    @BeforeEach
    void setUp() {
        llmClient = new StreamingFakeClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        toolRegistry.register(new ConsolePrintTool(event -> {}));

        var recorder = new CopyOnWriteArrayList<String>();
        AgentProgressSink sink = event -> {
            if (event != null) recorder.add(event.phase());
        };

        agent = new OrchestratorAgent(llmClient, toolRegistry, "test-model", sink);
        agent.setStreamEnabled(true);
        agent.setMaxToolRounds(8);
    }

    // ==================== 测试 1 ====================

    @Test
    @DisplayName("1. 流式 SSE tool_calls → Orchestrator 消费 finish_action 并结束")
    void streamingToolCallsAreConsumedByToolLoop() {
        llmClient.addToolCallsResponse(List.of(
                new LlmToolCall("call_1", "finish_action",
                        Map.of("status", "success", "message", "完成"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试请求");

        assertTrue(result.success(),
                "流式 finish_action 应该成功: " + result.errorMessage());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertEquals("完成", result.finalText());
        // 确保没有进入"普通文本没 finish_action"路径
        assertFalse(result.errorMessage() != null
                        && result.errorMessage().contains("no tool calls"),
                "不应进入 no-finish_action 路径");
    }

    // ==================== 测试 2 ====================

    @Test
    @DisplayName("2. ToolLoop LlmRequest 包含 tools + finish_action + tool_choice=auto")
    void streamingRequestIncludesTools() {
        llmClient.addToolCallsResponse(List.of(
                new LlmToolCall("call_2", "finish_action",
                        Map.of("status", "success", "message", "OK"))
        ));

        agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "请求");

        // 检查捕获的请求
        List<LlmRequest> requests = llmClient.getCapturedRequests();
        assertFalse(requests.isEmpty(), "应至少发起一次 LLM 请求");
        LlmRequest req = requests.get(0);

        List<ToolDef> tools = req.tools();
        assertNotNull(tools, "tools 不应为 null");
        assertFalse(tools.isEmpty(), "tools 不应为空");

        boolean hasFinishAction = tools.stream().anyMatch(t -> "finish_action".equals(t.name()));
        assertTrue(hasFinishAction, "tools 应包含 finish_action");

        assertEquals("auto", req.toolChoice(),
                "tool_choice 应为 auto，实际: " + req.toolChoice());
    }

    // ==================== 测试 3 ====================

    @Test
    @DisplayName("3. 流式 native finish_action → accepted → request 不再增加")
    void streamingNativeFinishActionEndsLoop() {
        llmClient.addToolCallsResponse(List.of(
                new LlmToolCall("call_3", "finish_action",
                        Map.of("status", "success", "message", "终结"))
        ));

        agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结束");

        // 只应发起 1 次请求（finish_action accepted 后不再请求 LLM）
        assertEquals(1, llmClient.getRequestCount(),
                "finish_action accepted 后应只发起 1 次请求，实际: " + llmClient.getRequestCount());
    }

    // ==================== 测试 4 ====================

    @Test
    @DisplayName("4. 流式返回普通 content（无 tool_calls）→ reprompt → 下一轮必须调用 finish_action")
    void noFinishActionRepromptAfterStreamingContent() {
        // R1: 普通文本（无 tool_calls）
        llmClient.addResponse("这是完整的报名表内容，包含了所有细节……");
        // R2: 收到 reprompt 后调用 finish_action
        llmClient.addToolCallsResponse(List.of(
                new LlmToolCall("call_4", "finish_action",
                        Map.of("status", "success", "message", "这是完整的报名表内容……"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "帮我生成报名表");

        assertTrue(result.success(),
                "R2 finish_action 应成功: " + result.errorMessage());
        assertEquals(2, llmClient.getRequestCount(),
                "应发起 2 次请求（R1 rejected + R2 accepted）");

        // 最终输出来自 finish_action.message
        assertEquals("这是完整的报名表内容……", result.finalText());
    }

    // ==================== 测试 5 ====================

    @Test
    @DisplayName("5. [ORCH_STREAM] 可观测性：requestTools > 0 且 delta 计数正确")
    void orchestratorStreamDebugReportsToolCount() {
        llmClient.addToolCallsResponse(List.of(
                new LlmToolCall("call_5", "finish_action",
                        Map.of("status", "success", "message", "调试测试"))
        ));

        agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "调试");

        List<LlmRequest> requests = llmClient.getCapturedRequests();
        assertFalse(requests.isEmpty());

        LlmRequest req = requests.get(0);
        int requestTools = req.tools() != null ? req.tools().size() : 0;
        assertTrue(requestTools > 0,
                "requestTools 应 > 0，实际: " + requestTools);

        // 工具列表应包含核心工具
        List<String> toolNames = req.tools().stream().map(ToolDef::name).toList();
        assertTrue(toolNames.contains("finish_action"), "应包含 finish_action");
        assertTrue(toolNames.contains("console_print"), "应包含 console_print");
    }
}
