package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
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
 * 验证 finish_action 通过文本 JSON fallback 被调用后立即结束 ToolLoop。
 */
@DisplayName("TEXT_FALLBACK finish_action 立即结束 ToolLoop")
class FinishActionFromTextFallbackImmediatelyEndsLoopTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("文本 JSON finish_action → 立即结束，发一次请求")
    void textFallbackFinishActionEndsLoopImmediately() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"文本 fallback 完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "Text fallback finish_action should succeed: " + result.errorMessage());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertTrue(result.finalText().contains("文本 fallback 完成"),
                "finalText should come from finish_action message");

        // 只有 1 次 LLM request
        assertEquals(1, fakeLlm.getRequestCount(),
                "Should NOT trigger next LLM request after accepted");
    }
}
