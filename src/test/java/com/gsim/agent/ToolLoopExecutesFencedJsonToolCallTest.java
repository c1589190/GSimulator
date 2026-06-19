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
 * 验证 ToolLoop 正确执行 fenced JSON 格式的 tool call：
 * ```json\n{"tool":"工具名","args":{...}}\n```
 * 包括各种 fence 变体。
 */
@DisplayName("ToolLoop 执行 fenced JSON tool call")
class ToolLoopExecutesFencedJsonToolCallTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new BranchCreateChildTool());
        toolRegistry.register(new EchoTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("标准 ```json fenced tool call 被执行")
    void standardFencedJsonExecuted() {
        String fenced = "```json\n"
                + "{\"tool\":\"branch_create_child\",\"args\":{"
                + "\"title\":\"第一回合\","
                + "\"initialInput\":\"罗德岛启程\""
                + "}}\n```";
        fakeLlm.addResponse(fenced);
        fakeLlm.addResponse("节点已创建。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "创建节点");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertEquals("branch_create_child", result.toolCalls().get(0).tool());
        assertFalse(result.finalText().contains("```"),
                "finalText must not contain fence markers");
    }

    @Test
    @DisplayName("空格 fence ``` json 格式被正确识别")
    void spaceInFenceLanguageExecuted() {
        String fenced = "``` json\n"
                + "{\"tool\":\"echo\",\"args\":{\"message\":\"from spaced fence\"}}\n"
                + "```";
        fakeLlm.addResponse(fenced);
        fakeLlm.addResponse("已执行。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertEquals("echo", result.toolCalls().get(0).tool());
    }

    @Test
    @DisplayName("无语言标记 fence ``` 格式被正确识别")
    void noLanguageFenceExecuted() {
        String fenced = "```\n"
                + "{\"tool\":\"echo\",\"args\":{\"message\":\"no lang tag\"}}\n"
                + "```";
        fakeLlm.addResponse(fenced);
        fakeLlm.addResponse("完成。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertEquals("echo", result.toolCalls().get(0).tool());
    }

    @Test
    @DisplayName("两个连续 fenced JSON tool calls 都被执行")
    void twoFencedToolCallsBothExecuted() {
        String response = "需要执行两个操作：\n\n"
                + "```json\n{\"tool\":\"echo\",\"args\":{\"message\":\"first\"}}\n```\n\n"
                + "然后：\n\n"
                + "```json\n{\"tool\":\"echo\",\"args\":{\"message\":\"second\"}}\n```";
        fakeLlm.addResponse(response);
        fakeLlm.addResponse("两个工具均已执行。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertEquals(2, result.toolCalls().size(),
                "Both fenced tool calls should be executed");
    }

    // ===== Fake Tools =====

    static class BranchCreateChildTool implements AgentTool {
        @Override public String name() { return "branch_create_child"; }
        @Override public String description() {
            return "创建子 branch 节点。参数: title(必填), initialInput(可选)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            String title = call.param("title", "new");
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item(title, "branch.b0001",
                            "branchId=branch.b0001 title=" + title, 1.0)));
        }
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
