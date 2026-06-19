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
 * 验证 ToolLoop 缓存 lastAssistantDraft，供 turn_settlement_save_last_response 使用。
 * 适配 finish_action 架构：草稿缓存发生在每次 assistant 回复时，
 * 最终 draft 来自 finish_action.message 或 post-guard 值。
 */
@DisplayName("ToolLoop 缓存 lastAssistantDraft")
class ToolLoopCachesLastAssistantDraftForSettlementTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("finalText 来自 finish_action.message → draft 非空")
    void draftIsCachedAfterChat() {
        // NL 文本不能直接结束，必须 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\","
                + "\"args\":{\"status\":\"success\","
                + "\"message\":\"任务已完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算本回合");

        assertTrue(result.success());
        String draft = agent.getLastAssistantDraft();
        assertNotNull(draft, "Draft should not be null");
        assertFalse(draft.isBlank(), "Draft should not be blank");
        assertTrue(draft.contains("任务已完成"));
    }

    @Test
    @DisplayName("ToolLoop 执行工具后 draft 来自 finish_action.message")
    void draftIsFinalTextNotToolJson() {
        // 第一轮：工具调用
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"list\"}}");
        // 第二轮：finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\","
                + "\"args\":{\"status\":\"success\","
                + "\"message\":\"基于推演内容，第一回合结算：玩家A已抵达龙门，获得通行证。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());

        String draft = agent.getLastAssistantDraft();
        assertNotNull(draft);
        assertFalse(draft.contains("\"tool\":\"echo\""),
                "Draft should NOT contain raw tool call JSON");
        assertTrue(draft.contains("第一回合结算"),
                "Draft should contain the actual settlement text");
    }

    @Test
    @DisplayName("多次对话后 draft 更新为最新 (finish_action 驱动)")
    void draftUpdatesOnEachCall() {
        // 第一次对话
        fakeLlm.addResponse("{\"tool\":\"finish_action\","
                + "\"args\":{\"status\":\"success\",\"message\":\"第一次回复。\"}}");
        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                List.of(), "msg1");
        String draft1 = agent.getLastAssistantDraft();
        assertEquals("第一次回复。", draft1);

        // 第二次对话 — 需要新响应
        fakeLlm.addResponse("{\"tool\":\"finish_action\","
                + "\"args\":{\"status\":\"success\",\"message\":\"第二次回复。\"}}");
        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                List.of(), "msg2");
        String draft2 = agent.getLastAssistantDraft();
        assertEquals("第二次回复。", draft2);

        assertNotEquals(draft1, draft2, "Draft should update across calls");
    }

    // ===== Stub =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo tool."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", "ok", 1.0)));
        }
    }
}
