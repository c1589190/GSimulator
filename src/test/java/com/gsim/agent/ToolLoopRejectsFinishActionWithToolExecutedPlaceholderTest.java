package com.gsim.agent;

import com.gsim.llm.FakeLlmClient;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 finish_action.message 中包含 [工具调用已执行] 或 [TOOL_RESULT]
 * 等伪造标记时被拒绝，要求重试。
 */
@DisplayName("ToolLoop 拒绝 finish_action.message 中的伪造工具标记")
class ToolLoopRejectsFinishActionWithToolExecutedPlaceholderTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("[工具调用已执行] 占位标记被拒绝")
    void toolExecutedPlaceholderIsRejected() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"操作完成。[工具调用已执行] 当前状态正常。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"操作完成。当前状态正常。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看状态");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size(),
                "First rejected, second accepted");
        assertFalse(result.finalText().contains("[工具调用已执行]"),
                "Accepted message must NOT contain placeholder");
    }

    @Test
    @DisplayName("[工具结果] 伪造标记被拒绝")
    void toolResultPlaceholderIsRejected() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"查询完成。\\n[工具结果] 状态OK\\n一切正常。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"查询完成。系统状态正常。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "状态");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertFalse(result.finalText().contains("[工具结果]"),
                "Accepted message must NOT contain [工具结果]");
    }

    @Test
    @DisplayName("[TOOL_RESULT] 英文伪造标记被拒绝")
    void englishToolResultPlaceholderIsRejected() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"Done. [TOOL_RESULT] OK\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"操作完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "状态");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertFalse(result.finalText().contains("[TOOL_RESULT]"));
    }
}
