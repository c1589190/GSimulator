package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmManager;
import com.gsim.llm.LlmToolCall;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 finish_action summary 字段以 ANSI 蓝色输出显示在 CLI。
 */
@DisplayName("finish_action summary 蓝色输出")
class FinishActionSummaryTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private CapturingProgressSink sink;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        sink = new CapturingProgressSink();
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model", sink);
    }

    @Test
    @DisplayName("finish_action 带 summary → 发出蓝字 publicMessage 事件")
    void finishActionWithSummaryEmitsBluePublicMessage() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success",
                                "message", "操作完成。",
                                "summary", "已查询玩家行动：共 3 条记录"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看行动");

        assertTrue(result.success());
        assertEquals("操作完成。", result.finalText());

        // 验证 publicMessage 事件包含蓝色 ANSI 码和 summary 内容
        List<AgentProgressEvent> publicMsgs = sink.events.stream()
                .filter(e -> AgentProgressEvent.AGENT_PUBLIC_MESSAGE.equals(e.phase()))
                .toList();
        assertFalse(publicMsgs.isEmpty(),
                "应有 publicMessage 事件（summary 蓝字）");

        AgentProgressEvent summaryEvent = publicMsgs.get(0);
        assertTrue(summaryEvent.detail().contains("\033[34m"),
                "summary 应包含 ANSI 蓝色码: " + summaryEvent.detail());
        assertTrue(summaryEvent.detail().contains("已查询玩家行动：共 3 条记录"),
                "summary 应包含摘要文本: " + summaryEvent.detail());
        assertTrue(summaryEvent.detail().contains("\033[0m"),
                "summary 应包含 ANSI 重置码");
    }

    @Test
    @DisplayName("finish_action 无 summary → 不产生 publicMessage 事件")
    void finishActionWithoutSummaryNoPublicMessage() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success",
                                "message", "操作完成。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals("操作完成。", result.finalText());

        // 验证没有 summary 相关的 publicMessage 事件
        List<AgentProgressEvent> blueMsgs = sink.events.stream()
                .filter(e -> AgentProgressEvent.AGENT_PUBLIC_MESSAGE.equals(e.phase())
                        && e.detail() != null
                        && e.detail().contains("\033[34m"))
                .toList();
        assertTrue(blueMsgs.isEmpty(),
                "无 summary 时不应产生蓝色 publicMessage 事件");
    }

    @Test
    @DisplayName("finish_action summary 为空 → 不产生 publicMessage 事件")
    void finishActionWithBlankSummaryNoPublicMessage() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success",
                                "message", "操作完成。",
                                "summary", "   "))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());

        List<AgentProgressEvent> blueMsgs = sink.events.stream()
                .filter(e -> AgentProgressEvent.AGENT_PUBLIC_MESSAGE.equals(e.phase())
                        && e.detail() != null
                        && e.detail().contains("\033[34m"))
                .toList();
        assertTrue(blueMsgs.isEmpty(),
                "空白 summary 不应产生蓝色 publicMessage 事件");
    }

    // ===== Helper =====

    static class CapturingProgressSink implements AgentProgressSink {
        final List<AgentProgressEvent> events = new java.util.ArrayList<>();
        @Override
        public void onProgress(AgentProgressEvent event) {
            events.add(event);
        }
    }
}
