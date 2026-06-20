package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmManager;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证连续 2 次非法工具意图后 ToolLoop 提前中止，
 * 不浪费后续轮次。
 */
@DisplayName("ToolLoop 连续 2 次非法工具意图中止")
class ToolLoopInvalidToolIntentStopsAfterTwoTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("连续 2 次 [调用 xxx] 格式 → 中止")
    void twoBracketInvokesAbort() {
        // Round 1: 括号非法
        fakeLlm.addResponse("[调用 player_action_list] {\"branchId\":\"branch.b0002\"}");
        // Round 2: 再次括号非法
        fakeLlm.addResponse("[调用 knowledge_search] {\"query\":\"test\"}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0002\n",
                List.of(), "查询");

        assertFalse(result.success(),
                "Should abort after 2 consecutive invalid tool intents");
        assertTrue(result.errorMessage() != null && !result.errorMessage().isEmpty(),
                "Should have error message");
        assertEquals(0, result.toolCalls().size(),
                "No tools should have been executed");
    }

    @Test
    @DisplayName("连续 2 次口头'调用 xxx 工具' → 中止")
    void twoVerbalToolIntentsAbort() {
        fakeLlm.addResponse("我需要调用 knowledge_search 工具来查询数据...");
        fakeLlm.addResponse("然后调用 player_action_list 工具获取行动记录...");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询");

        assertFalse(result.success(),
                "Should abort after 2 consecutive verbal tool intents");
    }

    @Test
    @DisplayName("1 次非法 + 1 次合法工具调用 → 计数器重置，不中止")
    void singleInvalidThenValidResetsCounter() {
        // Round 1: 非法
        fakeLlm.addResponse("[调用 player_action_list] {\"branchId\":\"branch.b0002\"}");
        // Round 2: 合法 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"收到纠正，已改用合法格式。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0002\n",
                List.of(), "查询");

        assertTrue(result.success(),
                "Single invalid + valid should succeed (counter reset)");
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
    }

    @Test
    @DisplayName("2 次 [工具结果] 伪输出 → 中止")
    void twoFakeToolResultsAbort() {
        fakeLlm.addResponse("[工具结果] 查询返回了结果。");
        fakeLlm.addResponse("[工具结果] player_action_list 已完成。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询");

        assertFalse(result.success(),
                "Should abort after 2 consecutive fake tool results");
    }
}
