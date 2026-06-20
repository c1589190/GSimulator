package com.gsim.agent;

import com.gsim.llm.FakeLlmManager;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 finish_action.message 包含 raw JSON 时被拒绝，要求重试。
 * 拒绝类型：
 * 1. fenced JSON (```json...```)
 * 2. bare tool JSON ({"tool":"...",...})
 * 3. raw tool output JSON ({"activeRoot":"...",...})
 */
@DisplayName("ToolLoop 拒绝 finish_action.message 中的 raw JSON")
class ToolLoopRejectsFinishActionWithRawToolJsonTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("finish_action.message 含 fenced JSON → 被拒绝 → 重试成功")
    void fencedJsonInMessageIsRejected() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"查询完成。\\n```json\\n{\\\"activeRoot\\\":\\\"cna-rk\\\"}\\n```\\n当前状态正常。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"查询完成。当前根节点为 cna-rk，分支为 branch.b0000-start。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看状态");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size(),
                "Two finish_action calls: first rejected, second accepted");
        assertFalse(result.finalText().contains("```"),
                "Accepted message must NOT contain fenced JSON");
    }

    @Test
    @DisplayName("finish_action.message 含 bare tool JSON → 被拒绝")
    void bareToolJsonInMessageIsRejected() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"操作完成。{\\\"tool\\\":\\\"root_status\\\",\\\"args\\\":{}}\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"操作完成。当前状态正常。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "状态");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertFalse(result.finalText().contains("\"tool\""),
                "Accepted message must NOT contain bare tool JSON");
    }

    @Test
    @DisplayName("finish_action.message 含 raw tool output JSON → 被拒绝")
    void rawToolOutputInMessageIsRejected() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"{\\\"activeRoot\\\":\\\"cna-rk\\\",\\\"activeBranch\\\":\\\"branch.b0000-start\\\"}\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"当前处于根节点，分支为 branch.b0000-start。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "状态");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertFalse(result.finalText().startsWith("{"),
                "Accepted message must NOT start with JSON");
    }
}
