package com.gsim.agent;

import com.gsim.llm.FakeLlmClient;
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
 * 验证 Agent 在没有 branch_next_turn 工具结果的情况下
 * 不得声称「已进入 b0002」或「已切换到下一回合」。
 *
 * <p>这个测试覆盖 guardSuccessClaimWithoutToolBacking 在 branch 场景下的表现。
 */
@DisplayName("Agent 不得在无 branch_next_turn 结果时声称已进入分支")
class AgentDoesNotClaimEnteredBranchWithoutBranchNextTurnResultTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubBranchNextTurnTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("无工具执行时声称「已进入 b0002」触发系统警告")
    void claimEnteredB0002WithoutToolTriggersWarning() {
        // LLM 直接声称已进入 b0002，没有任何工具调用
        fakeLlm.addResponse("已进入 b0002，这是第二回合的推演内容。" +
                "当前分支 b0002 已激活，时间推进到下一阶段。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "进入下一回合");

        assertTrue(result.success());
        assertEquals(0, result.toolCalls().size(),
                "No tools should be executed");
        assertTrue(result.finalText().contains("[系统提示]"),
                "Should contain system warning about unbacked claim");
    }

    @Test
    @DisplayName("无工具执行时声称「已切换到 b0002」触发系统警告")
    void claimSwitchedToB0002WithoutToolTriggersWarning() {
        fakeLlm.addResponse("操作完成。已切换到 branch.b0002，" +
                "当前活跃分支已更新。推演继续...");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "切换分支");

        assertTrue(result.success());
        assertEquals(0, result.toolCalls().size());
        assertTrue(result.finalText().contains("[系统提示]"));
    }

    @Test
    @DisplayName("有 branch_next_turn 执行时声称「已进入 b0001」不触发警告（合法场景）")
    void claimEnteredWithToolBackingDoesNotTriggerWarning() {
        // 第一轮：LLM 输出 tool call
        fakeLlm.addResponse("{\"tool\":\"branch_next_turn\"," +
                "\"args\":{\"worldTime\":\"泰拉纪年1096年冬\"}}");
        // 第二轮：LLM 基于工具结果自然语言回复
        fakeLlm.addResponse("已创建并进入 branch.b0001。当前回合：1，" +
                "世界时间：泰拉纪年1096年冬。推演开始...");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "开始第一回合");

        assertTrue(result.success());
        assertTrue(result.toolCalls().size() >= 1,
                "Should have at least 1 tool execution");
        assertFalse(result.finalText().contains("[系统提示]"),
                "Should NOT warn when tools back the claim");
    }

    @Test
    @DisplayName("无工具执行时声称「第一回合节点已创建」触发警告")
    void claimFirstTurnCreatedWithoutToolTriggersWarning() {
        fakeLlm.addResponse("第一回合节点已创建，当前切换到 branch.b0001-first-turn。" +
                "这是开始序言：在泰拉大陆的某个角落...");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "创建第一回合");

        assertTrue(result.success());
        assertEquals(0, result.toolCalls().size());
        assertTrue(result.finalText().contains("[系统提示]"),
                "Should warn about unbacked '已创建' claim");
    }

    // ===== Stub =====

    static class StubBranchNextTurnTool implements AgentTool {
        @Override
        public String name() { return "branch_next_turn"; }
        @Override
        public String description() { return "Create next turn node and switch."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("下一回合: branch.b0001", "branch.b0001",
                            "status=OK\ncreatedBranchId=branch.b0001\n" +
                                    "activeBranchId=branch.b0001\nswitched=true\n",
                            1.0)));
        }
    }
}
