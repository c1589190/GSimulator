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
 * 验证用户询问"第二回合有没有玩家行动记录"时，
 * Agent 调用 player_action_list → finish_action 闭环。
 */
@DisplayName("Agent 玩家行动记录查询返回结果")
class AgentCurrentTurnActionRecordQueryReturnsResultTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubPlayerActionListTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("问'第二回合有没有玩家行动记录' → player_action_list + finish_action")
    void queryActionRecordsReturnsResult() {
        // Round 1: player_action_list
        fakeLlm.addResponse("{\"tool\":\"player_action_list\",\"args\":{}}");
        // Round 2: finish_action with summary
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"当前 branch.b0002 有 3 条玩家行动记录：\\n"
                + "1. act0001: 玩家A — 前往龙门\\n"
                + "2. act0002: 玩家B — 侦查周边\\n"
                + "3. act0003: 玩家A — 发动攻击\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0002\n",
                List.of(), "确认一下第二回合有没有玩家行动记录");

        assertTrue(result.success(),
                "Action query should succeed: " + result.errorMessage());
        assertEquals(2, result.toolCalls().size(),
                "Expected player_action_list + finish_action");
        assertEquals("player_action_list", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
        assertTrue(result.finalText().contains("玩家"),
                "finalText should contain player action summary");
        assertFalse(result.finalText().contains("\"tool\""),
                "finalText should be clean of raw JSON");
        assertFalse(result.finalText().contains("[工具结果]"),
                "finalText should be clean of tool result markers");
    }

    @Test
    @DisplayName("查询无行动记录的节点 → finish_action 返回'没有行动记录'")
    void queryEmptyActionResult() {
        // Round 1: player_action_list → 空结果
        fakeLlm.addResponse("{\"tool\":\"player_action_list\",\"args\":{}}");
        // Round 2: finish_action with empty summary
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"当前 branch.b0002 没有玩家行动记录。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0002\n",
                List.of(), "这个节点有没有行动");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertTrue(result.finalText().contains("没有")
                        || result.finalText().contains("无"),
                "finalText should indicate no actions found");
    }

    @Test
    @DisplayName("纯自然语言回应 → R1 显示给用户 → R2 finish_action 结束")
    void plainTextResponseToActionQueryIsRejected() {
        // Round 1: 纯 NL → 显示给用户 → 提醒
        fakeLlm.addResponse("当前回合有 3 条玩家行动记录: ...");
        // Round 2: 收到提醒后调用 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"当前回合有 3 条玩家行动记录：act001, act002, act003\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0002\n",
                List.of(), "确认一下第二回合有没有玩家行动记录");

        assertTrue(result.success(),
                "R2 finish_action 应成功结束: " + result.errorMessage());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
    }

    // ===== Stub =====

    static class StubPlayerActionListTool implements AgentTool {
        @Override public String name() { return "player_action_list"; }
        @Override public String description() { return "列出当前节点所有玩家行动记录。"; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("玩家行动列表", "branch.b0002",
                            "### 玩家行动记录\n\n"
                                    + "- act0001: 玩家A — 前往龙门\n"
                                    + "- act0002: 玩家B — 侦查周边\n"
                                    + "- act0003: 玩家A — 发动攻击\n",
                            1.0)));
        }
    }
}
