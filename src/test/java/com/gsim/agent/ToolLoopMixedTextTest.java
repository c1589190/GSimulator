package com.gsim.agent;

import com.gsim.llm.FakeLlmManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies ToolLoop correctly executes tool calls embedded in mixed natural language + JSON.
 */
class ToolLoopMixedTextTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    void mixedChineseTextWithRawJsonExecutesTool() {
        // LLM outputs: natural language + JSON, then final NL answer
        fakeLlm.addResponse("让我先看看当前状态：\n\n{\"tool\":\"echo\",\"args\":{\"message\":\"hello\"}}");
        fakeLlm.addResponse("已执行完毕，当前状态正常。");

        var result = agent.run(List.of(), "test", "turn info");
        assertNotNull(result.finalText());
        assertTrue(result.finalText().contains("已执行完毕"),
                "Should get final NL answer: " + result.finalText());
        assertFalse(result.finalText().contains("{\"tool\":"),
                "Final text must not contain raw JSON");
        assertEquals(1, result.toolCalls().size(), "Should have 1 tool call");
        assertEquals("echo", result.toolCalls().get(0).tool());
    }

    @Test
    void mixedTextWithFencedJsonExecutesTool() {
        fakeLlm.addResponse("需要查询一下：\n```json\n{\"tool\":\"echo\",\"args\":{\"message\":\"hi\"}}\n```\n查到后再回复。");
        fakeLlm.addResponse("已经查到了。");

        var result = agent.run(List.of(), "test", "turn info");
        assertEquals(1, result.toolCalls().size());
        assertEquals("echo", result.toolCalls().get(0).tool());
        assertFalse(result.finalText().contains("```"),
                "Final text must not contain fenced JSON");
    }

    @Test
    void pureNaturalLanguageDoesNotTriggerTool() {
        fakeLlm.setNextResponse("这是一个正常回复，不需要调用工具。");
        var result = agent.run(List.of(), "test", "turn info");
        assertEquals(0, result.toolCalls().size());
        assertTrue(result.finalText().contains("正常回复"));
    }

    @Test
    void multipleToolCallsInSequence() {
        fakeLlm.addResponse("第一步：{\"tool\":\"echo\",\"args\":{\"message\":\"step1\"}}");
        fakeLlm.addResponse("第二步：{\"tool\":\"echo\",\"args\":{\"message\":\"step2\"}}");
        fakeLlm.addResponse("全部完成。");

        var result = agent.run(List.of(), "test", "turn info");
        assertEquals(2, result.toolCalls().size());
        assertTrue(result.finalText().contains("全部完成"));
    }

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo tool for testing."; }
        @Override
        public ToolResult execute(ToolCall call) {
            String msg = call.param("message", "no message");
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", msg, 1.0)));
        }
    }
}
