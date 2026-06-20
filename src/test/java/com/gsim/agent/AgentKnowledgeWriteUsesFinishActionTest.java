package com.gsim.agent;

import com.gsim.llm.FakeLlmManager;
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
 * 端到端测试：knowledge_upsert → finish_action。
 * 验证知识写入工作流以 finish_action 结束，finalText 干净。
 */
@DisplayName("Agent 知识写入工作流使用 finish_action 结束")
class AgentKnowledgeWriteUsesFinishActionTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubKnowledgeUpsertTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("用户要求记录信息 → knowledge_upsert + finish_action → 2 个 tool calls，finalText 干净")
    void knowledgeWriteEndsWithFinishAction() {
        fakeLlm.addResponse("{\"tool\":\"knowledge_upsert\",\"args\":{"
                + "\"key\":\"切尔诺伯格\",\"content\":\"乌萨斯境内感染者城市，已废弃。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已将切尔诺伯格信息录入知识库。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "记录切尔诺伯格的信息");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertEquals("knowledge_upsert", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
        assertTrue(result.finalText().contains("切尔诺伯格"),
                "finalText should contain the knowledge topic");
        assertFalse(result.finalText().contains("\"tool\""),
                "finalText should be clean of raw JSON");
    }

    @Test
    @DisplayName("知识写入后直接 finish_action，无额外回合")
    void knowledgeWriteNoExtraRounds() {
        fakeLlm.addResponse("{\"tool\":\"knowledge_upsert\",\"args\":{"
                + "\"key\":\"龙门\",\"content\":\"龙门是炎国移动城邦，以商业闻名。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"龙门信息已录入。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "添加龙门设定");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size(),
                "Exactly 2 calls: upsert + finish_action, no extra rounds");
    }

    // ===== Stub =====

    static class StubKnowledgeUpsertTool implements AgentTool {
        @Override public String name() { return "knowledge_upsert"; }
        @Override public String description() { return "写入知识库。参数: key, content。"; }
        @Override
        public ToolResult execute(ToolCall call) {
            String key = call.param("key", "unknown");
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item(key, "kn-" + key,
                            "key=" + key + " written", 1.0)));
        }
    }
}
