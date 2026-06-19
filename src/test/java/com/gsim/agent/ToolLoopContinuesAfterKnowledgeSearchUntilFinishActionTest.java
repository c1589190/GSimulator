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
 * 验证 knowledge_search 之后 ToolLoop 继续执行直到 finish_action。
 * 读取工具 ≠ 结束节点，必须显式 finish_action。
 */
@DisplayName("knowledge_search 后继续 ToolLoop 直到 finish_action")
class ToolLoopContinuesAfterKnowledgeSearchUntilFinishActionTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubKnowledgeSearchTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("knowledge_search → 继续 → finish_action → 结束，2 个 tool calls")
    void knowledgeSearchThenFinishAction() {
        fakeLlm.addResponse("{\"tool\":\"knowledge_search\",\"args\":{"
                + "\"query\":\"龙门 近卫局\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"知识库查询完成：找到 3 条关于龙门的记录。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查龙门资料");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertEquals("knowledge_search", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
        assertFalse(result.finalText().contains("\"tool\""),
                "finalText must not contain raw tool JSON");
    }

    @Test
    @DisplayName("knowledge_search + knowledge_search → finish_action")
    void multipleSearchesThenFinishAction() {
        fakeLlm.addResponse("{\"tool\":\"knowledge_search\",\"args\":{"
                + "\"query\":\"乌萨斯\"}}");
        fakeLlm.addResponse("{\"tool\":\"knowledge_search\",\"args\":{"
                + "\"query\":\"感染者\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已查询乌萨斯和感染者相关信息。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询背景");

        assertTrue(result.success());
        assertEquals(3, result.toolCalls().size());
    }

    // ===== Stub =====

    static class StubKnowledgeSearchTool implements AgentTool {
        @Override public String name() { return "knowledge_search"; }
        @Override public String description() { return "搜索知识库。"; }
        @Override
        public ToolResult execute(ToolCall call) {
            String query = call.param("query", "");
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item(query, "result-1",
                            "search result for: " + query, 0.9)));
        }
    }
}
