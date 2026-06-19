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
 * 验证 ToolLoop 检测并拒绝模型伪造的 [工具结果] 和 {key=value} 输出。
 * 适配 finish_action 架构：伪造检测通过 finish_action 验证 + 后处理守卫双重保障。
 */
@DisplayName("ToolLoop 拒绝伪造的 [工具结果] 和 {key=value}")
class ToolLoopRejectsFakeBracketToolResultWithoutRealToolCallTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    // ===== hasFakeBracketToolResult 单元测试 =====

    @Test
    @DisplayName("hasFakeBracketToolResult 检测 [工具结果] 模式")
    void detectsFakeBracketToolResultPattern() {
        String text = "让我查看当前分支结构...\n\n[工具结果] {title=branch.b0000-start, branchId=branch.b0000-start}";

        boolean result = OrchestratorAgent.hasFakeBracketToolResult(text, List.of());
        assertTrue(result, "Should detect [工具结果] pattern without real tool calls");
    }

    @Test
    @DisplayName("hasFakeBracketToolResult 检测 {mode=tree} 模式")
    void detectsFakeModeTreePattern() {
        String text = "以下是节点树：{mode=tree}\n- branch.b0000-start";

        boolean result = OrchestratorAgent.hasFakeBracketToolResult(text, List.of());
        assertTrue(result, "Should detect {mode=tree} pattern without real tool calls");
    }

    @Test
    @DisplayName("hasFakeBracketToolResult 检测 {branchId=branch.b0002} 模式")
    void detectsFakeBranchIdPattern() {
        String text = "已创建新节点 {branchId=branch.b0002, status=OK}，可以开始推演。";

        boolean result = OrchestratorAgent.hasFakeBracketToolResult(text, List.of());
        assertTrue(result, "Should detect {branchId=...} fake key-value block");
    }

    @Test
    @DisplayName("hasFakeBracketToolResult 不误报有真实 tool_call 的场景")
    void doesNotFlagWhenRealToolCallsExist() {
        String text = "工具执行结果：status=OK\ncreatedBranchId=branch.b0001";

        var toolCalls = List.of(new OrchestratorAgent.ToolCallRecord(
                "branch_next_turn", java.util.Map.of("worldTime", "1096"),
                ToolResult.ok("branch_next_turn", List.of())));
        boolean result = OrchestratorAgent.hasFakeBracketToolResult(text, toolCalls);
        assertFalse(result, "Should NOT flag when real tool calls exist");
    }

    @Test
    @DisplayName("hasFakeBracketToolResult 不误报正常自然语言")
    void doesNotFlagNormalText() {
        String text = "当前系统状态正常。branch.b0000-start 是活跃的根分支。";

        boolean result = OrchestratorAgent.hasFakeBracketToolResult(text, List.of());
        assertFalse(result, "Normal text should not be flagged");
    }

    @Test
    @DisplayName("hasFakeBracketToolResult null 和空文本返回 false")
    void nullAndEmptyReturnFalse() {
        assertFalse(OrchestratorAgent.hasFakeBracketToolResult(null, List.of()));
        assertFalse(OrchestratorAgent.hasFakeBracketToolResult("", List.of()));
        assertFalse(OrchestratorAgent.hasFakeBracketToolResult("   ", List.of()));
    }

    // ===== stripFakeBracketToolResult 单元测试 =====

    @Test
    @DisplayName("stripFakeBracketToolResult 移除 [工具结果] 块")
    void stripsBracketToolResultBlocks() {
        String text = "节点创建成功。\n\n[工具结果] {title=branch.b0001, branchId=branch.b0001}\n\n继续推演...";

        String stripped = OrchestratorAgent.stripFakeBracketToolResult(text);
        assertFalse(stripped.contains("[工具结果]"),
                "Should remove [工具结果] block: " + stripped);
        assertTrue(stripped.contains("节点创建成功"),
                "Should preserve leading text");
        assertTrue(stripped.contains("继续推演"),
                "Should preserve trailing text");
    }

    @Test
    @DisplayName("stripFakeBracketToolResult 移除 {mode=tree} 块")
    void stripsModeTreeBlock() {
        String text = "查看结构：{mode=tree} 当前节点列表如下。";

        String stripped = OrchestratorAgent.stripFakeBracketToolResult(text);
        assertFalse(stripped.contains("{mode=tree}"),
                "Should remove {mode=tree}: " + stripped);
    }

    @Test
    @DisplayName("stripFakeBracketToolResult 移除 {branchId=...} 块")
    void stripsBranchIdBlock() {
        String text = "已进入 {branchId=branch.b0002} 新节点。";

        String stripped = OrchestratorAgent.stripFakeBracketToolResult(text);
        assertFalse(stripped.contains("{branchId="),
                "Should remove {branchId=...}: " + stripped);
    }

    // ===== 全 ToolLoop 集成测试 (finish_action) =====

    @Test
    @DisplayName("ToolLoop 集成：finish_action.message 中 [工具结果] 被拒绝")
    void toolLoopFiltersFakeBracketResult() {
        // finish_action 的 message 包含伪造的 [工具结果] → 被 validateFinishActionMessage 拒绝
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"让我查看分支结构。\\n\\n[工具结果] {mode=tree}\\nbranch.b0000-start\\n  └── branch.b0001 (已创建)\\n\\n已确认进入 branch.b0001。\"}}");
        // 重试：干净的 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"当前根分支 branch.b0000-start，有一个子节点 branch.b0001。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看分支并进入下一回合");

        assertTrue(result.success());
        // [工具结果] 应被拒绝（resolved by retry）
        assertFalse(result.finalText().contains("[工具结果]"),
                "Final text should NOT contain [工具结果] pattern");
        // {mode=tree} 应被拒绝
        assertFalse(result.finalText().contains("{mode=tree}"),
                "Final text should NOT contain {mode=tree}");
    }

    @Test
    @DisplayName("ToolLoop 集成：finish_action.message 中 {branchId=...} 被后处理守卫过滤")
    void toolLoopFiltersFakeBranchIdBlock() {
        // finish_action message 含 {branchId=...} 但不含 [工具结果] 等其他 banned pattern
        // → validateFinishActionMessage 不拒绝
        // → 到达 post-loop guard hasFakeBracketToolResult → 被过滤
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"节点创建完毕 {branchId=branch.b0002, status=OK}。现在开始推演第一回合的内容...\"}}");
        // 重试
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"节点创建完毕。现在开始推演第一回合的内容...\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "创建第一回合");

        assertTrue(result.success());
        assertFalse(result.finalText().contains("{branchId="),
                "Final text should NOT contain {branchId=...} fake block");
    }

    // ===== Stub =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo tool."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", "ok", 1.0)));
        }
    }
}
