package com.gsim.agent;

import com.gsim.llm.FakeLlmClient;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证当用户要求「进入下一回合」时，Agent 通过 ToolLoop 调用 branch_next_turn。
 */
@DisplayName("Agent 使用 branch_next_turn 响应下一回合请求")
class AgentUsesBranchNextTurnForNextRoundRequestTest {

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
    @DisplayName("用户说「进入下一回合」→ LLM 调用 branch_next_turn → 工具执行成功")
    void userSaysEnterNextTurnTriggersToolCall() {
        // 第一轮：LLM 输出 tool call JSON
        fakeLlm.addResponse("{\"tool\":\"branch_next_turn\"," +
                "\"args\":{\"worldTime\":\"泰拉纪年1096年冬\"}}");
        // 第二轮：LLM 基于工具结果输出自然语言
        fakeLlm.addResponse("已进入下一回合：branch.b0001，时间：泰拉纪年1096年冬。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "进入下一回合");

        assertTrue(result.success());
        assertTrue(result.toolCalls().size() >= 1,
                "Should execute at least 1 tool call");
        assertEquals("branch_next_turn", result.toolCalls().get(0).tool(),
                "Should call branch_next_turn for next turn request");
        assertTrue(result.toolCalls().get(0).result().success(),
                "branch_next_turn should succeed");
    }

    @Test
    @DisplayName("用户说「开始第一回合」→ LLM 调用 branch_next_turn")
    void userSaysStartFirstTurnTriggersToolCall() {
        fakeLlm.addResponse("{\"tool\":\"branch_next_turn\"," +
                "\"args\":{\"worldTime\":\"泰拉纪年1096年冬\",\"title\":\"第一回合·龙门\"}}");
        fakeLlm.addResponse("第一回合已开始，场景：龙门之夜。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "开始第一回合");

        assertTrue(result.success());
        assertTrue(result.toolCalls().size() >= 1);
        assertEquals("branch_next_turn", result.toolCalls().get(0).tool());
    }

    @Test
    @DisplayName("用户说「next turn」→ LLM 调用 branch_next_turn 而非 branch_create_child")
    void nextTurnUsesBranchNextTurnNotCreateChild() {
        fakeLlm.addResponse("{\"tool\":\"branch_next_turn\"," +
                "\"args\":{\"worldTime\":\"1096 Winter\"}}");
        fakeLlm.addResponse("Turn 2 ready.");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0001\n",
                List.of(), "next turn");

        assertTrue(result.success());
        assertTrue(result.toolCalls().size() >= 1);
        // 验证使用的是 branch_next_turn，不是 branch_create_child
        boolean hasNextTurn = result.toolCalls().stream()
                .anyMatch(tc -> "branch_next_turn".equals(tc.tool()));
        assertTrue(hasNextTurn, "Should use branch_next_turn for next turn operations");
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
                            "status=OK\ncreatedBranchId=branch.b0001\nparentBranchId=branch.b0000-start\n" +
                                    "activeBranchId=branch.b0001\nturn=1\nworldTime=" +
                                    call.param("worldTime", "?") + "\nswitched=true\n",
                            1.0)));
        }
    }
}
