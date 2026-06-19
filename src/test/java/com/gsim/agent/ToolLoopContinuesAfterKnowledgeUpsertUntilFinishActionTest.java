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
 * 验证 knowledge_upsert 之后 ToolLoop 继续执行直到 finish_action。
 * 工具执行 ≠ 结束，必须显式 finish_action。
 */
@DisplayName("knowledge_upsert 后继续 ToolLoop 直到 finish_action")
class ToolLoopContinuesAfterKnowledgeUpsertUntilFinishActionTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubKnowledgeUpsertTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("knowledge_upsert → 继续 → finish_action → 结束，2 个 tool calls")
    void knowledgeUpsertThenFinishAction() {
        fakeLlm.addResponse("{\"tool\":\"knowledge_upsert\",\"args\":{"
                + "\"key\":\"龙门\",\"content\":\"龙门是炎国移动城邦。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已将龙门信息写入知识库。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "记录龙门信息");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertEquals("knowledge_upsert", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
        assertFalse(result.finalText().contains("\"tool\""),
                "finalText must not contain raw tool JSON");
    }

    @Test
    @DisplayName("连续 knowledge_upsert + knowledge_upsert → finish_action")
    void multipleKnowledgeUpsertsThenFinishAction() {
        fakeLlm.addResponse("{\"tool\":\"knowledge_upsert\",\"args\":{"
                + "\"key\":\"罗德岛\",\"content\":\"罗德岛是感染者救助组织。\"}}");
        fakeLlm.addResponse("{\"tool\":\"knowledge_upsert\",\"args\":{"
                + "\"key\":\"整合运动\",\"content\":\"整合运动是感染者武装组织。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"罗德岛和整合运动信息已录入。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "记录阵营");

        assertTrue(result.success());
        assertEquals(3, result.toolCalls().size());
        assertEquals("knowledge_upsert", result.toolCalls().get(0).tool());
        assertEquals("knowledge_upsert", result.toolCalls().get(1).tool());
        assertEquals("finish_action", result.toolCalls().get(2).tool());
    }

    // ===== Stub =====

    static class StubKnowledgeUpsertTool implements AgentTool {
        @Override public String name() { return "knowledge_upsert"; }
        @Override public String description() { return "写入知识库。"; }
        @Override
        public ToolResult execute(ToolCall call) {
            String key = call.param("key", "unknown");
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item(key, key, "upserted key=" + key, 1.0)));
        }
    }
}
