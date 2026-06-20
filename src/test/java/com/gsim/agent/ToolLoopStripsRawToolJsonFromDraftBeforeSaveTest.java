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
 * 验证 ToolLoop/draft 缓存前剥离 raw JSON、[工具结果] 和系统提示。
 * 适配 finish_action 架构：draft 来自 finish_action.message。
 */
@DisplayName("ToolLoop 缓存前剥离 raw JSON (finish_action)")
class ToolLoopStripsRawToolJsonFromDraftBeforeSaveTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    // ===== stripRawToolJson 单元测试 =====

    @Test
    @DisplayName("stripRawToolJson 剥离 fenced JSON block")
    void stripRawToolJsonRemovesFencedJson() {
        String text = "## 第一回合结算\n\n玩家抵达龙门。\n\n" +
                "```json\n{\"tool\":\"turn_settlement_save\",\"args\":{\"settlement\":\"...\"}}\n```\n\n" +
                "结算总结。";

        String result = OrchestratorAgent.stripRawToolJson(text);

        assertTrue(result.contains("第一回合结算"), "Should preserve settlement text: " + result);
        assertTrue(result.contains("玩家抵达龙门"), "Should preserve content text");
        assertFalse(result.contains("```json"), "Should remove fenced block");
        assertFalse(result.contains("turn_settlement_save"), "Should remove tool name");
    }

    @Test
    @DisplayName("stripRawToolJson 剥离裸 JSON tool call")
    void stripRawToolJsonRemovesBareJsonToolCall() {
        String text = "结算完成。\n{\"tool\":\"turn_settlement_save\",\"args\":{\"settlement\":\"长文本...\"}}\n\n" +
                "下一回合即将开始。";

        String result = OrchestratorAgent.stripRawToolJson(text);

        assertTrue(result.contains("结算完成"), "Should preserve leading text: " + result);
        assertTrue(result.contains("下一回合即将开始"), "Should preserve trailing text");
        assertFalse(result.contains("\"tool\":\"turn_settlement_save\""),
                "Should remove bare tool call JSON");
    }

    @Test
    @DisplayName("stripRawToolJson 对不含 tool JSON 的文本保持原样")
    void stripRawToolJsonPreservesCleanText() {
        String text = "## 第一回合结算\n\n玩家抵达龙门，获得通行证。";

        String result = OrchestratorAgent.stripRawToolJson(text);

        assertEquals(text, result, "Clean text should remain unchanged");
    }

    // ===== ToolLoop 集成测试 =====

    @Test
    @DisplayName("draft 不含 [工具结果] 伪造标记（finish_action 验证拒绝）")
    void draftStripsFakeBracketResult() {
        // 第一轮：finish_action 的 message 包含 [工具结果] → validateFinishActionMessage 拒绝
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"## 第一回合结算 | 玩家抵达龙门。 | [工具结果] {status=OK, branchId=branch.b0001} | 结算已完成。\"}}");
        // 第二轮：干净的 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"第一回合结算：玩家抵达龙门。结算已完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算");

        assertTrue(result.success());
        String draft = agent.getLastAssistantDraft();

        assertNotNull(draft);
        assertTrue(draft.contains("第一回合结算"), "Draft should preserve settlement text: " + draft);
        assertFalse(draft.contains("[工具结果]"), "Draft should NOT contain [工具结果]: " + draft);
        assertFalse(draft.contains("{status=OK"), "Draft should NOT contain fake kv block: " + draft);
    }

    @Test
    @DisplayName("有真实工具调用后的 draft 来自 finish_action.message")
    void draftIsCachedAfterRealToolCalls() {
        // 第一轮：真实工具调用
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"read\"}}");
        // 第二轮：finish_action（含单行自然语言，避免 JSON 换行问题）
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"基于读取到的推演内容，第一回合结算如下：玩家A已与龙门势力建立初步联系，获得通行证。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "读取并结算");

        assertTrue(result.success());
        String draft = agent.getLastAssistantDraft();

        assertNotNull(draft);
        assertTrue(draft.contains("第一回合结算"), "Draft should contain settlement: " + draft);
        assertFalse(draft.contains("\"tool\""), "Draft should NOT contain raw tool JSON: " + draft);
        assertFalse(draft.contains("[系统提示]"), "Draft should NOT contain system warning: " + draft);
    }

    @Test
    @DisplayName("finish_action.message 自然语言回复被完整缓存为 draft")
    void cleanResponseIsFullyCachedAsDraft() {
        // finish_action 多行 message 通过 \\n 避免 JSON 字面换行
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"第一回合结算：玩家A抵达龙门，与当地势力初步接触，获得通行证。关键事件：1.调查情报 2.遇到罗德岛干员\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算");

        assertTrue(result.success());
        String draft = agent.getLastAssistantDraft();

        assertNotNull(draft);
        assertFalse(draft.isBlank());
        assertTrue(draft.contains("第一回合结算"), "Draft should contain settlement text: " + draft);
        assertTrue(draft.contains("罗德岛干员"), "Draft should contain full content: " + draft);
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
