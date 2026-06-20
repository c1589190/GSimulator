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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ToolLoop 正确执行纯 JSON 格式的 tool call：
 * {"tool":"工具名","args":{...}}
 */
@DisplayName("ToolLoop 执行纯 JSON tool call")
class ToolLoopExecutesPureJsonToolCallTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        toolRegistry.register(new BranchCreateChildTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("纯 JSON tool call 被提取并执行")
    void pureJsonExecuted() {
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"hello\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\",\"message\":\"工具已执行完毕。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertEquals("echo", result.toolCalls().get(0).tool());
        assertFalse(result.finalText().contains("{\"tool\""),
                "finalText must not contain raw JSON");
    }

    @Test
    @DisplayName("大参数 pure JSON tool call（如 branch_create_child）被正确执行")
    void largeArgPureJsonExecuted() {
        String json = "{\"tool\":\"branch_create_child\",\"args\":{"
                + "\"title\":\"第一回合 — 博士苏醒\","
                + "\"worldTime\":\"泰拉纪年1096年冬\","
                + "\"initialInput\":\"博士在罗德岛医疗舱苏醒。\""
                + "}}";
        fakeLlm.addResponse(json);
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\",\"message\":\"第一回合节点已创建：branch.b0001-first-turn。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "创建第一回合");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertEquals("branch_create_child", result.toolCalls().get(0).tool());
        assertEquals("第一回合 — 博士苏醒",
                result.toolCalls().get(0).args().get("title"));
    }

    @Test
    @DisplayName("连续两次纯 JSON tool call 都被执行")
    void twoConsecutivePureJsonCalls() {
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"first\"}}");
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"second\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\",\"message\":\"两次工具调用均已完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertEquals(3, result.toolCalls().size());
        assertEquals("echo", result.toolCalls().get(0).tool());
        assertEquals("echo", result.toolCalls().get(1).tool());
        assertEquals("finish_action", result.toolCalls().get(2).tool());
        assertTrue(result.finalText().contains("完成"));
    }

    // ===== Fake Tools =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo tool."; }
        @Override
        public ToolResult execute(ToolCall call) {
            String msg = call.param("message", "");
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", msg, 1.0)));
        }
    }

    static class BranchCreateChildTool implements AgentTool {
        @Override public String name() { return "branch_create_child"; }
        @Override public String description() {
            return "从当前 branch 创建子节点。参数: title(必填), initialInput(可选), worldTime(可选)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            String title = call.param("title", "new");
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item(title, "branch.b0001",
                            "branchId=branch.b0001 title=" + title, 1.0)));
        }
    }
}
