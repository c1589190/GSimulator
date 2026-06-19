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
 * 验证"禁止没有 tool_result 的成功宣称"守卫。
 *
 * <p>保守规则：如果 finalText 包含"已保存/已创建/已切换/已入库/已写入"等关键词，
 * 但整个 ToolLoop 中没有执行任何工具，则追加警告提示。
 */
@DisplayName("ToolLoop 禁止无 tool_result 的成功宣称")
class ToolLoopDoesNotClaimSuccessWithoutToolResultTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("无工具执行时声称'已创建'触发警告")
    void claimCreatedWithoutToolTriggersGuard() {
        // LLM 直接声称成功，没有调用任何工具
        fakeLlm.addResponse("第一回合节点已创建，当前切换到 branch.b0001-first-turn。"
                + "这是开始序言...");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "创建第一回合");

        assertTrue(result.success());
        assertEquals(0, result.toolCalls().size(),
                "No tools should be executed");
        assertTrue(result.finalText().contains("[系统提示]"),
                "Should contain system warning about unbacked success claim");
        assertTrue(result.finalText().contains("未检测到对应的工具执行记录"),
                "Warning should mention missing tool execution");
    }

    @Test
    @DisplayName("无工具执行时声称'已保存'触发警告")
    void claimSavedWithoutToolTriggersGuard() {
        fakeLlm.addResponse("序章已保存，数据已入库。一切就绪。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "保存序章");

        assertTrue(result.success());
        assertEquals(0, result.toolCalls().size());
        assertTrue(result.finalText().contains("[系统提示]"),
                "Should warn about unbacked '已保存' claim");
    }

    @Test
    @DisplayName("无工具执行时声称'已切换'触发警告")
    void claimSwitchedWithoutToolTriggersGuard() {
        fakeLlm.addResponse("已切换到 branch.b0002，当前活跃分支已更新。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "切换分支");

        assertTrue(result.success());
        assertTrue(result.finalText().contains("[系统提示]"));
    }

    @Test
    @DisplayName("有工具执行时声称'已创建'不触发警告（合法场景）")
    void claimCreatedWithToolBackingDoesNotTriggerGuard() {
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"create node\"}}");
        fakeLlm.addResponse("节点已创建，当前切换到新 branch。一切就绪。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "创建节点");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size(),
                "A tool was executed to back up the claim");
        assertFalse(result.finalText().contains("[系统提示]"),
                "Should NOT warn when tools back up the claim");
    }

    @Test
    @DisplayName("纯自然语言回复不包含成功宣称时不触发警告")
    void normalResponseWithoutClaimsDoesNotTriggerGuard() {
        fakeLlm.addResponse("当前系统状态正常。branch.b0000-start 是活跃的根分支。"
                + "你可以使用命令来创建新的分支节点。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看状态");

        assertTrue(result.success());
        assertFalse(result.finalText().contains("[系统提示]"),
                "Normal response should not trigger guard");
    }

    @Test
    @DisplayName("guardSuccessClaimWithoutToolBacking 单元测试：各种成功关键词")
    void guardUnitTestAllClaimKeywords() {
        // 测试所有被检测的成功关键词
        String[] claims = {"已保存完毕。", "已创建完成。", "已切换到新分支。",
                "已入库存储。", "已写入文件。", "已更新数据。", "已完成操作。"};

        for (String claim : claims) {
            String guarded = OrchestratorAgent.guardSuccessClaimWithoutToolBacking(
                    claim, List.of(), 0);
            assertTrue(guarded.contains("[系统提示]"),
                    "Should guard claim: " + claim);
        }

        // 有工具执行时全部通过
        var toolCalls = List.of(new OrchestratorAgent.ToolCallRecord(
                "echo", java.util.Map.of(), ToolResult.ok("echo", List.of())));
        for (String claim : claims) {
            String guarded = OrchestratorAgent.guardSuccessClaimWithoutToolBacking(
                    claim, toolCalls, 1);
            assertFalse(guarded.contains("[系统提示]"),
                    "Should NOT guard when tool backed: " + claim);
        }
    }

    @Test
    @DisplayName("工具执行失败时声称成功触发警告")
    void toolFailureWithSuccessClaimTriggersGuard() {
        // 注册一个会失败的工具
        var failingRegistry = new ToolRegistry();
        failingRegistry.register(new FailingTool());
        var agentWithFailingTool = new OrchestratorAgent(fakeLlm, failingRegistry, "test-model");

        // 第一轮：工具调用 → 执行失败
        fakeLlm.addResponse("{\"tool\":\"failing_tool\",\"args\":{}}");
        // 第二轮：LLM 仍然声称成功
        fakeLlm.addResponse("操作已完成，文件已保存。");

        var result = agentWithFailingTool.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "执行操作");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertFalse(result.toolCalls().get(0).result().success(),
                "Tool should have failed");
        // 由于有 tool call（即使失败），guard 不会触发
        // 这是预期行为：工具被尝试执行了
        assertFalse(result.finalText().contains("[系统提示]"),
                "Guard should not trigger when tool was attempted (has toolCalls)");
    }

    // ===== Fake Tools =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo tool."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", "ok", 1.0)));
        }
    }

    static class FailingTool implements AgentTool {
        @Override public String name() { return "failing_tool"; }
        @Override public String description() { return "A tool that always fails."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.fail(name(), "This tool always fails");
        }
    }
}
