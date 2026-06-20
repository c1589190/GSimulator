package com.gsim.agent;

import com.gsim.agent.tool.ConsolePrintTool;
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
 * 验证 console_print 不结束 ToolLoop。
 * Agent 调用 console_print 后仍需调用 finish_action。
 */
@DisplayName("console_print 不结束 ToolLoop")
class ConsolePrintDoesNotEndToolLoopTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new ConsolePrintTool(AgentProgressSink.NOOP));
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("console_print → finish_action：两轮，不提前结束")
    void consolePrintThenFinishActionUsesTwoRounds() {
        // R1: console_print 输出报名表
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("non-stream", "console_print",
                        Map.of("message", "# 报名表\\n\\n请填写以下内容："))));
        // R2: finish_action 结束
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("non-stream", "finish_action",
                        Map.of("status", "success",
                                "message", "报名表已显示在上方。请复制填写。"))));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "给我生成一个报名表");

        assertTrue(result.success(), "Should succeed after finish_action");
        assertEquals(2, result.toolCalls().size(),
                "Should have 2 tool calls: console_print + finish_action");
        assertEquals("console_print", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
    }

    @Test
    @DisplayName("仅 console_print 不结束 → 被提示需要 finish_action")
    void onlyConsolePrintDoesNotEnd() {
        // R1: console_print
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("non-stream", "console_print",
                        Map.of("message", "模板内容..."))));
        // R2: 没有调用任何工具（模型以为结束了）→ reminder
        fakeLlm.addResponse("已完成。");
        // R3: finish_action
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("non-stream", "finish_action",
                        Map.of("status", "success",
                                "message", "模板已输出，请查收。"))));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "显示模板");

        assertTrue(result.success());
        // console_print (R1) + finish_action (R3) = 2 tool calls
        assertTrue(result.toolCalls().size() >= 2,
                "Should have at least console_print + finish_action, got " + result.toolCalls().size());
    }
}
