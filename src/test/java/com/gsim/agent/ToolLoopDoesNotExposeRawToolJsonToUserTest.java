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
 * 验证 raw tool JSON 不泄露给用户：
 * <ul>
 * <li>fenced JSON block 被 stripRawToolJson 移除</li>
 * <li>pure JSON tool call 被 stripRawToolJson 移除</li>
 * <li>正常自然语言不被误伤</li>
 * <li>finalText 经过 ToolLoop 后不含 raw JSON</li>
 * </ul>
 */
@DisplayName("ToolLoop 不将 raw tool JSON 暴露给用户")
class ToolLoopDoesNotExposeRawToolJsonToUserTest {

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
    @DisplayName("stripRawToolJson 移除标准 fenced JSON")
    void stripRawToolJsonRemovesFencedBlock() {
        String text = "操作进行中：\n```json\n{\"tool\":\"echo\",\"args\":{\"message\":\"hi\"}}\n```\n已完成。";
        String result = OrchestratorAgent.stripRawToolJson(text);
        assertFalse(result.contains("```"),
                "Fence markers should be stripped");
        assertFalse(result.contains("{\"tool\""),
                "Tool JSON should be stripped");
        assertTrue(result.contains("[工具调用已执行]"),
                "Should be replaced with indicator, got: " + result);
        assertTrue(result.contains("操作进行中"),
                "Natural language before fence should be preserved");
        assertTrue(result.contains("已完成"),
                "Natural language after fence should be preserved");
    }

    @Test
    @DisplayName("stripRawToolJson 移除内联 fenced JSON")
    void stripRawToolJsonRemovesInlineFencedJson() {
        String text = "```json{\"tool\":\"echo\",\"args\":{\"msg\":\"x\"}}```";
        String result = OrchestratorAgent.stripRawToolJson(text);
        assertFalse(result.contains("{\"tool\""), "Should strip inline tool JSON");
    }

    @Test
    @DisplayName("stripRawToolJson 移除裸露 pure JSON tool call")
    void stripRawToolJsonRemovesBareToolCall() {
        String text = "执行：{\"tool\":\"echo\",\"args\":{\"message\":\"test\"}} ——完成。";
        String result = OrchestratorAgent.stripRawToolJson(text);
        assertFalse(result.contains("{\"tool\""),
                "Bare tool JSON should be stripped, got: " + result);
    }

    @Test
    @DisplayName("stripRawToolJson 不误伤正常文本")
    void stripRawToolJsonPreservesNormalText() {
        String text = "当前系统状态正常。branch.b0000-start 是活跃分支。"
                + "使用 /root status 查看详情。";
        String result = OrchestratorAgent.stripRawToolJson(text);
        assertEquals(text, result, "Normal text should not be modified");
    }

    @Test
    @DisplayName("ToolLoop 完整流程中 finalText 不含 raw JSON")
    void toolLoopFinalTextFreeOfRawJson() {
        // 即使工具被正确提取，finalText 也应不含 raw JSON
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"step1\"}}");
        fakeLlm.addResponse("操作已成功完成。当前 branch.b0000-start 状态正常。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "执行操作");

        assertTrue(result.success());
        assertFalse(result.finalText().contains("{\"tool\""),
                "finalText must NOT contain raw tool JSON");
        assertFalse(result.finalText().contains("[TOOL_RESULT]"),
                "finalText must NOT contain [TOOL_RESULT] marker");
        assertTrue(result.finalText().length() > 10,
                "finalText should be substantial natural language");
    }

    @Test
    @DisplayName("多工具调用后 finalText 不含 raw JSON")
    void multipleToolCallsFinalTextIsClean() {
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"a\"}}");
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"b\"}}");
        fakeLlm.addResponse("所有操作已完成。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "执行");

        assertEquals(2, result.toolCalls().size());
        String ft = result.finalText();
        assertFalse(ft.contains("{\"tool\""), "finalText must be clean: " + ft);
        assertFalse(ft.contains("```"), "No fence markers: " + ft);
    }

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
}
