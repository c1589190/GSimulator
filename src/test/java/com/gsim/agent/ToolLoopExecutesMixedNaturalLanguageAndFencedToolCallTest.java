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
 * 验证 ToolLoop 正确从混合自然语言 + fenced JSON 中提取并执行 tool call。
 * 这是 P0 bug 的精确复现场景。
 */
@DisplayName("ToolLoop 从混合自然语言+fenced JSON 中提取 tool call")
class ToolLoopExecutesMixedNaturalLanguageAndFencedToolCallTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new SimulationContentAppendTool());
        toolRegistry.register(new BranchCreateChildTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("P0 复现：中文文本 + fenced JSON branch_create_child 被正确执行")
    void chineseTextWithFencedBranchCreateChildExecuted() {
        // 第一轮：simulation_content_append 工具调用
        fakeLlm.addResponse("{\"tool\":\"simulation_content_append\","
                + "\"args\":{\"content\":\"序章内容...\"}}");
        // 第二轮：中文 + fenced JSON — 这是 P0 bug 场景
        String mixedResponse = "序章已保存（sim0001）。现在创建第一回合节点。\n\n"
                + "```json\n"
                + "{\"tool\":\"branch_create_child\",\"args\":{"
                + "\"title\":\"第一回合 — 博士苏醒\","
                + "\"worldTime\":\"泰拉纪年1096年冬\","
                + "\"initialInput\":\"博士在罗德岛医疗舱苏醒。\""
                + "}}\n"
                + "```";
        fakeLlm.addResponse(mixedResponse);
        // 第三轮：自然语言
        fakeLlm.addResponse("第一回合节点已创建：branch.b0001-first-turn。"
                + "当前 active branch 已切换到 branch.b0001。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "创建第一回合并写序言");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size(),
                "Both simulation_content_append and branch_create_child should be executed");
        assertEquals("simulation_content_append", result.toolCalls().get(0).tool());
        assertEquals("branch_create_child", result.toolCalls().get(1).tool());
        assertEquals("第一回合 — 博士苏醒",
                result.toolCalls().get(1).args().get("title"));

        // finalText 不得包含 raw JSON 或 fence
        String ft = result.finalText();
        assertFalse(ft.contains("```"),
                "finalText must NOT contain fence markers: " + ft);
        assertFalse(ft.contains("{\"tool\""),
                "finalText must NOT contain raw tool JSON: " + ft);
        assertTrue(ft.contains("branch.b0001"),
                "finalText should contain branch ID: " + ft);
    }

    @Test
    @DisplayName("长中文文本 + 大参数 fenced JSON 工具调用正确执行")
    void longChineseTextWithLargeArgFencedJsonExecuted() {
        String longText = "根据当前世界设定，泰拉大陆正处于多事之秋。"
                + "罗德岛作为一家致力于感染者救助的医药公司，"
                + "已在多个国家建立据点。现在需要创建新的分支节点。\n\n"
                + "```json\n"
                + "{\"tool\":\"branch_create_child\",\"args\":{"
                + "\"title\":\"龙门特别行动\","
                + "\"worldTime\":\"泰拉纪年1097年春\","
                + "\"initialInput\":\"龙门近卫局发来协助请求。\""
                + "}}\n"
                + "```";
        fakeLlm.addResponse(longText);
        fakeLlm.addResponse("龙门特别行动节点已创建：branch.b0002。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0001\n",
                List.of(), "创建龙门行动节点");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertEquals("branch_create_child", result.toolCalls().get(0).tool());
        assertEquals("龙门特别行动",
                result.toolCalls().get(0).args().get("title"));
    }

    @Test
    @DisplayName("自然语言中嵌入纯 JSON tool call 也被正确执行")
    void naturalLanguageWithEmbeddedRawJsonExecuted() {
        fakeLlm.addResponse("我先查一下状态：{\"tool\":\"branch_create_child\","
                + "\"args\":{\"title\":\"状态检查\"}}");
        fakeLlm.addResponse("状态检查节点已创建。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "检查");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertFalse(result.finalText().contains("{\"tool\""),
                "finalText must NOT contain raw JSON");
    }

    // ===== Fake Tools =====

    static class SimulationContentAppendTool implements AgentTool {
        @Override public String name() { return "simulation_content_append"; }
        @Override public String description() { return "追加推演内容到当前 branch。"; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("sim0001", "序章",
                            "saved content id=sim0001", 1.0)));
        }
    }

    static class BranchCreateChildTool implements AgentTool {
        @Override public String name() { return "branch_create_child"; }
        @Override public String description() {
            return "创建子 branch 节点。参数: title(必填), initialInput(可选), worldTime(可选)。";
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
