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
 * 验证 "进入第一回合" 工作流（适配 finish_action 架构）：
 * <ol>
 * <li>从根节点开始，先调 root_status 确认状态</li>
 * <li>再调 branch_create_child 创建第一回合节点</li>
 * <li>以 finish_action 结束</li>
 * <li>不会在 root_status 之后停止</li>
 * <li>不暴露 raw tool result</li>
 * </ol>
 */
@DisplayName("进入第一回合工作流 (finish_action)")
class StartFirstTurnWorkflowTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FakeRootStatusTool());
        toolRegistry.register(new FakeBranchCreateChildTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    // ========== 完整工作流：root_status → branch_create_child → finish_action ==========

    @Test
    @DisplayName("进入第一回合：先 root_status 确认，再 branch_create_child 创建，finish_action 结束")
    void startFirstTurnUsesRootStatusThenCreatesBranch() {
        // 第一轮: root_status
        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        // 第二轮: branch_create_child
        fakeLlm.addResponse("{\"tool\":\"branch_create_child\",\"args\":{\"title\":\"第一回合：罗德岛抵达边境\",\"initialInput\":\"泰拉11090年，罗德岛小队抵达乌萨斯边境感染者救援点。\"}}");
        // 第三轮: finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"【开始序言】泰拉11090年，寒风越过乌萨斯边境……已创建第一回合节点：branch.b0001-first-turn。当前 active branch：branch.b0001-first-turn\"}}");

        var result = agent.chatWithContextSession(
                "# BaseContext\n\nbranch: branch.b0000-start\n",
                List.of(),
                "检测一下你能不能创建下一回合资料，先简单出一个开始序言，给我复制，然后直接进入第一回合");

        assertTrue(result.success());
        assertEquals(3, result.toolCalls().size(),
                "Should have root_status + branch_create_child + finish_action");
        assertEquals("root_status", result.toolCalls().get(0).tool(),
                "First tool call should be root_status");
        assertEquals("branch_create_child", result.toolCalls().get(1).tool(),
                "Second tool call should be branch_create_child");
        assertEquals("finish_action", result.toolCalls().get(2).tool(),
                "Third tool call should be finish_action");

        String ft = result.finalText();
        assertTrue(ft.contains("开始序言") || ft.contains("泰拉"),
                "finalText should contain prologue, got: " + ft);
        assertTrue(ft.contains("branch.b0001"),
                "finalText should contain branch ID, got: " + ft);
        assertFalse(ft.contains("[TOOL_RESULT]"),
                "finalText must NOT contain tool result markers");
        assertFalse(ft.contains("{\"activeRoot\""),
                "finalText must NOT contain raw JSON");
    }

    // ========== 不在 root_status 后停止 ==========

    @Test
    @DisplayName("执行 root_status 后 ToolLoop 继续，以 finish_action 结束")
    void doesNotStopAtRootStatus() {
        // 两轮调用：root_status → finish_action
        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已确认当前在根节点 branch.b0000-start。但我发现缺少 branch_create_child 工具，无法自动创建第一回合节点。你可以使用以下命令手动创建：/root status 查看状态\"}}");

        var result = agent.chatWithContextSession(
                "# BaseContext\n\nbranch: branch.b0000-start\n",
                List.of(),
                "进入第一回合");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertFalse(result.finalText().startsWith("[TOOL_RESULT]"));
        assertFalse(result.finalText().startsWith("{"));
        // 应该继续执行并给出有意义的回答，而不是停在 root_status
        assertTrue(result.finalText().length() > 20,
                "finalText should be substantial, not truncated tool result");
    }

    // ========== finalText 不含 raw tool output ==========

    @Test
    @DisplayName("最终输出不含 raw tool result")
    void finalAnswerCleanFromRawToolOutput() {
        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        fakeLlm.addResponse("{\"tool\":\"branch_create_child\",\"args\":{\"title\":\"序章\",\"initialInput\":\"世界初始\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已为你创建第一回合节点。以下是开始序言：在遥远的泰拉大陆...\"}}");

        var result = agent.chatWithContextSession(
                "# BaseContext\n\nbranch: branch.b0000-start\n",
                List.of(),
                "创建第一回合和序言");

        String ft = result.finalText();
        assertFalse(ft.contains("[TOOL_RESULT]"), "No tool result markers: " + ft);
        assertFalse(ft.contains("{\"activeRoot\""), "No raw tool JSON: " + ft);
        assertFalse(ft.startsWith("{"), "Not starting with JSON: " + ft);
        assertFalse(ft.startsWith("[工具"), "Not starting with tool prefix: " + ft);
    }

    // ========== 缺少工具时诚实说明 ==========

    @Test
    @DisplayName("缺少 branch_create_child 时 LLM 诚实说明")
    void honestWhenMissingTools() {
        // 只用 root_status，没有 branch_create_child，但有 finish_action
        var limitedRegistry = new ToolRegistry();
        limitedRegistry.register(new FakeRootStatusTool());
        limitedRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        var limitedAgent = new OrchestratorAgent(fakeLlm, limitedRegistry, "test-model");

        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"当前处于根节点 branch.b0000-start。但我无法自动创建第一回合节点，因为缺少 branch_create_child 工具。建议使用现有命令手动创建。\"}}");

        var result = limitedAgent.chatWithContextSession(
                "# BaseContext\n\nbranch: branch.b0000-start\n",
                List.of(),
                "创建第一回合");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        // 不应该假装创建成功
        assertFalse(result.finalText().contains("已创建第一回合") && result.toolCalls().size() == 2,
                "Should not claim success when missing tools: " + result.finalText());
    }

    // ========== tool_result 反馈格式 ==========

    @Test
    @DisplayName("tool_result 反馈格式中不含易混淆的 final answer 模式")
    void toolResultFormatIsNotAmbiguous() {
        var result = ToolResult.ok("root_status", List.of(
                new ToolResult.Item("status", "cna-rk",
                        "activeRoot: cna-rk\nactiveBranch: branch.b0000-start\nisAtRootBranch: true", 1.0)));

        String feedback = OrchestratorAgent.buildToolResultFeedback("root_status", result);

        // 不应该看起来像最终回答
        assertFalse(feedback.startsWith("工具返回") || feedback.startsWith("[工具返回]") || feedback.startsWith("[工具结果]"),
                "Feedback should NOT look like final answer prefix");
        // 应该包含明确指令
        assertTrue(feedback.contains("继续完成用户请求"),
                "Feedback should instruct LLM to continue");
        // 应该用标记包裹
        assertTrue(feedback.startsWith("[TOOL_RESULT]"),
                "Feedback should start with [TOOL_RESULT]");
        assertTrue(feedback.contains("[/TOOL_RESULT]"),
                "Feedback should contain closing [/TOOL_RESULT]");
    }

    // ========== 序言存在性 ==========

    @Test
    @DisplayName("进入第一回合后 finalText 包含可复制的开始序言")
    void finalAnswerContainsPrologue() {
        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        fakeLlm.addResponse("{\"tool\":\"branch_create_child\",\"args\":{\"title\":\"第一回合\",\"initialInput\":\"罗德岛启程\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"【开始序言】泰拉11090年...（叙事内容）已创建第一回合节点：branch.b0001。当前 active branch：branch.b0001\"}}");

        var result = agent.chatWithContextSession(
                "# BaseContext\n\nbranch: branch.b0000-start\n",
                List.of(),
                "出序言，进第一回合");

        assertTrue(result.success());
        assertTrue(result.finalText().contains("开始序言") || result.finalText().contains("泰拉") || result.finalText().contains("叙事"),
                "finalText should contain prologue content, got: " + result.finalText());
    }

    // ========== active branch 信息 ==========

    @Test
    @DisplayName("最终回答包含当前 active branch")
    void finalAnswerContainsActiveBranch() {
        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        fakeLlm.addResponse("{\"tool\":\"branch_create_child\",\"args\":{\"title\":\"序章：启程\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已创建序章节点。当前 active branch：branch.b0001。开始序言：世界之初...\"}}");

        var result = agent.chatWithContextSession(
                "# BaseContext\n\nbranch: branch.b0000-start\n",
                List.of(),
                "开始");

        assertTrue(result.success());
        assertTrue(result.finalText().contains("branch"),
                "finalText should mention branch ID, got: " + result.finalText());
    }

    // ===== Fake Tools =====

    static class FakeRootStatusTool implements AgentTool {
        @Override
        public String name() { return "root_status"; }

        @Override
        public String description() {
            return "查询当前 root 状态: active root ID, active branch ID, 是否在根节点, knowledge db path。";
        }

        @Override
        public ToolResult execute(ToolCall call) {
            String content = "activeRoot: cna-rk\n"
                    + "activeBranch: branch.b0000-start\n"
                    + "isAtRootBranch: true\n"
                    + "knowledgeDbPath: data/worlds/cna-rk/knowledge.db";
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("root_status", "cna-rk", content, 1.0)));
        }
    }

    static class FakeBranchCreateChildTool implements AgentTool {
        @Override
        public String name() { return "branch_create_child"; }

        @Override
        public String description() {
            return "从当前 branch 创建子节点并切换到新 branch。参数: title(必填), initialInput(可选), worldTime(可选)。";
        }

        @Override
        public ToolResult execute(ToolCall call) {
            String title = call.param("title", "new-node");
            String branchId = "branch.b0001-first-turn";
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item(title, branchId,
                            "branchId=" + branchId + " parent=branch.b0000-start isAtRoot=false title=" + title,
                            1.0)));
        }
    }
}
