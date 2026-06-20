package com.gsim.agent;

import com.gsim.llm.FakeLlmManager;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 finish_action 单独调用即可结束 ToolLoop。
 * 最基础的 finish_action 用例。
 */
@DisplayName("finish_action 单独调用结束 ToolLoop")
class FinishActionToolEndsLoopTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("finish_action 单独调用 → ToolLoop 结束，finalText = message")
    void finishActionAloneEndsLoop() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"当前系统状态正常。你可以继续操作。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看状态");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertEquals("当前系统状态正常。你可以继续操作。", result.finalText());
    }

    @Test
    @DisplayName("finish_action status=partial → ToolLoop 仍结束")
    void finishActionPartialStatusEndsLoop() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"partial\","
                + "\"message\":\"部分操作完成，但知识库查询未能返回完整结果。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertTrue(result.finalText().contains("部分"));
    }

    @Test
    @DisplayName("finish_action status=failed 诚实报告")
    void finishActionFailedEndsLoop() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"failed\","
                + "\"message\":\"无法完成操作：缺少必要工具。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "执行操作");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertTrue(result.finalText().contains("无法完成"));
    }

    @Test
    @DisplayName("finish_action status=needs_user_input → 结束并等待用户")
    void finishActionNeedsUserInputEndsLoop() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"needs_user_input\","
                + "\"message\":\"需要确认：是否进入下一回合？当前回合已结算完毕。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertTrue(result.finalText().contains("需要确认"));
    }
}
