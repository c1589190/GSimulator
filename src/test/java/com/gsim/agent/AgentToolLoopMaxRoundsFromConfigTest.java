package com.gsim.agent;

import com.gsim.llm.FakeLlmManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 OrchestratorAgent 使用通过 setMaxToolRounds() 注入的配置值，
 * 而非硬编码常量。
 */
@DisplayName("OrchestratorAgent — maxToolRounds 可配置")
class AgentToolLoopMaxRoundsFromConfigTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        toolRegistry.register(new EchoTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("setMaxToolRounds(3) 后第 3 轮工具调用未 finish → 应在第 3 轮后返回错误")
    void maxRounds3FailsOnThirdRound() {
        agent.setMaxToolRounds(3);

        // 3 轮 echo 调用，不调用 finish_action
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r1\"}}");
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r2\"}}");
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r3\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试 3 轮上限");

        assertFalse(result.success(),
                "Should fail when no finish_action within 3 rounds");
        assertTrue(result.toolCalls().size() >= 3,
                "All 3 echo calls should be recorded");
    }

    @Test
    @DisplayName("setMaxToolRounds(10) 后第 3 轮 finish_action 正常结束")
    void maxRounds10FinishOnRound3Succeeds() {
        agent.setMaxToolRounds(10);

        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r1\"}}");
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r2\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"任务完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试 10 轮上限");

        assertTrue(result.success(),
                "finish_action on round 3 within 10-round limit should succeed");
        assertEquals(3, result.toolCalls().size());
    }

    @Test
    @DisplayName("setMaxToolRounds(1) 后第 1 轮未 finish → 立即返回错误")
    void maxRounds1FailsImmediately() {
        agent.setMaxToolRounds(1);

        // Round 1: echo，不调用 finish_action
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"only\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试 1 轮上限");

        assertFalse(result.success(),
                "Should fail when no finish_action within 1 round");
    }

    @Test
    @DisplayName("未调用 setMaxToolRounds 时默认 32，第 5 轮 finish 可正常结束")
    void defaultMaxRounds32FinishOnRound5Succeeds() {
        // 不调用 setMaxToolRounds → 使用默认 8

        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r1\"}}");
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r2\"}}");
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r3\"}}");
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r4\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"第 5 轮完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试默认 8 轮");

        assertTrue(result.success(),
                "finish_action on round 5 within default 8 should succeed");
        assertEquals(5, result.toolCalls().size());
    }

    @Test
    @DisplayName("getMaxToolRounds() 返回当前值")
    void getterReturnsCurrentValue() {
        assertEquals(32, agent.getMaxToolRounds(), "default should be 32");

        agent.setMaxToolRounds(15);
        assertEquals(15, agent.getMaxToolRounds());

        agent.setMaxToolRounds(3);
        assertEquals(3, agent.getMaxToolRounds());
    }

    @Test
    @DisplayName("setMaxToolRounds(999) 大值直接生效（无上限）")
    void setterAcceptsLargeValue() {
        agent.setMaxToolRounds(999);
        assertEquals(999, agent.getMaxToolRounds(),
                "999 should be accepted (no upper clamp)");
    }

    @Test
    @DisplayName("setMaxToolRounds(0) 应 clamp 到 1")
    void setterClampsToLowerBound() {
        agent.setMaxToolRounds(0);
        assertEquals(1, agent.getMaxToolRounds(),
                "0 should be clamped to 1 by setter");
    }

    // ===== Stub =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", "ok", 1.0)));
        }
    }
}
