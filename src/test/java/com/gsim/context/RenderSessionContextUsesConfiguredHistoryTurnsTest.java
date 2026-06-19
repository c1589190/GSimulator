package com.gsim.context;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.context.session.SessionMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 renderSessionContext 按配置的 historyTurns 过滤消息。
 */
@DisplayName("RenderSessionContext — 可配置历史轮数")
class RenderSessionContextUsesConfiguredHistoryTurnsTest {

    private static final String BASE_CONTEXT = "# Base Context\n\nTest world context.";

    /**
     * 构造 N 轮对话，每轮 = 1 user + 1 assistant（共 2N 条消息）。
     * 使用零填充避免子串匹配（"turn 01" ≠ "turn 10"）。
     */
    private static List<SessionMessage> buildTurns(int turnCount) {
        List<SessionMessage> messages = new ArrayList<>();
        for (int t = 0; t < turnCount; t++) {
            int turnNum = t + 1;
            String label = String.format("%02d", turnNum);
            messages.add(new SessionMessage(
                    "u-" + label, "cs-1", "b-1", "user", "chat_user",
                    "User input turn " + label, Instant.now(), Map.of()));
            messages.add(new SessionMessage(
                    "a-" + label, "cs-1", "b-1", "assistant", "chat_response",
                    "Assistant response turn " + label, Instant.now(), Map.of()));
        }
        return messages;
    }

    @Test
    @DisplayName("historyTurns=3 时只保留最后 3 轮")
    void last3TurnsWith10Total() {
        List<SessionMessage> allMessages = buildTurns(10); // 20 messages
        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, allMessages, "current input",
                        3, 4000);

        // 前 7 轮的 user/assistant 不应出现
        assertFalse(result.contains("User input turn 01"), "turn 01 should be dropped");
        assertFalse(result.contains("User input turn 07"), "turn 07 should be dropped");
        // 后 3 轮应出现
        assertTrue(result.contains("User input turn 08"), "turn 08 should be kept");
        assertTrue(result.contains("User input turn 09"), "turn 09 should be kept");
        assertTrue(result.contains("User input turn 10"), "turn 10 should be kept");
        assertTrue(result.contains("Assistant response turn 10"), "turn 10 assistant should be kept");
        // 当前输入应出现
        assertTrue(result.contains("current input"));
    }

    @Test
    @DisplayName("historyTurns=12（默认）时 10 轮全部保留")
    void default12TurnsKeepsAll10() {
        List<SessionMessage> allMessages = buildTurns(10);
        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, allMessages, "current input",
                        12, 4000);

        assertTrue(result.contains("User input turn 01"), "turn 01 should be kept");
        assertTrue(result.contains("User input turn 10"), "turn 10 should be kept");
    }

    @Test
    @DisplayName("historyTurns=1 时只保留最后一轮")
    void last1TurnWith10Total() {
        List<SessionMessage> allMessages = buildTurns(10);
        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, allMessages, "current input",
                        1, 4000);

        assertFalse(result.contains("User input turn 09"), "turn 09 should be dropped");
        assertTrue(result.contains("User input turn 10"), "turn 10 should be kept");
    }

    @Test
    @DisplayName("空消息列表不会崩溃")
    void emptyMessagesReturnsBaseContext() {
        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, List.of(), "current input",
                        12, 4000);

        assertTrue(result.contains(BASE_CONTEXT));
        assertTrue(result.contains("current input"));
        assertFalse(result.contains("Session Messages"),
                "Should not have Session Messages section when empty");
    }

    @Test
    @DisplayName("null 消息列表不会崩溃")
    void nullMessagesReturnsBaseContext() {
        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, null, "current input",
                        12, 4000);

        assertTrue(result.contains(BASE_CONTEXT));
        assertTrue(result.contains("current input"));
    }

    @Test
    @DisplayName("filterByTurns 静态方法正确处理 turn 边界")
    void filterByTurnsStaticMethodCorrect() {
        List<SessionMessage> messages = buildTurns(5);

        // 保留最后 3 轮 → 6 条消息（user+assistant×3）
        List<SessionMessage> filtered = OrchestratorAgent.filterByTurns(messages, 3);
        assertEquals(6, filtered.size());
        assertEquals("u-03", filtered.get(0).id());
        assertEquals("a-05", filtered.get(5).id());

        // 保留 10 轮 → 全部
        List<SessionMessage> all = OrchestratorAgent.filterByTurns(messages, 10);
        assertEquals(10, all.size());
    }

    @Test
    @DisplayName("filterByTurns 在 maxTurns=0 时返回空列表")
    void filterByTurnsZeroReturnsEmpty() {
        List<SessionMessage> messages = buildTurns(3);
        List<SessionMessage> filtered = OrchestratorAgent.filterByTurns(messages, 0);
        assertTrue(filtered.isEmpty());
    }

    @Test
    @DisplayName("filterByTurns 在负值时返回空列表")
    void filterByTurnsNegativeReturnsEmpty() {
        List<SessionMessage> messages = buildTurns(3);
        List<SessionMessage> filtered = OrchestratorAgent.filterByTurns(messages, -1);
        assertTrue(filtered.isEmpty());
    }

    @Test
    @DisplayName("filterByTurns null 输入返回空列表")
    void filterByTurnsNullReturnsEmpty() {
        List<SessionMessage> filtered = OrchestratorAgent.filterByTurns(null, 5);
        assertTrue(filtered.isEmpty());
    }

    @Test
    @DisplayName("只有 user 消息计为 turn，中间 tool 消息归入同一 turn")
    void toolMessagesBelongToSameTurn() {
        List<SessionMessage> messages = new ArrayList<>();
        // Turn 1: user → tool_call → tool_result → assistant
        messages.add(msg("t1-u", "user"));
        messages.add(msg("t1-tc", "tool"));
        messages.add(msg("t1-tr", "tool"));
        messages.add(msg("t1-a", "assistant"));
        // Turn 2: user → assistant
        messages.add(msg("t2-u", "user"));
        messages.add(msg("t2-a", "assistant"));

        // historyTurns=1 → 只保留从最后一个 user 开始的所有消息
        List<SessionMessage> filtered = OrchestratorAgent.filterByTurns(messages, 1);
        assertEquals(2, filtered.size());
        assertEquals("t2-u", filtered.get(0).id());
        assertEquals("t2-a", filtered.get(1).id());

        // historyTurns=2 → 全部
        List<SessionMessage> filtered2 = OrchestratorAgent.filterByTurns(messages, 2);
        assertEquals(6, filtered2.size());
    }

    private static SessionMessage msg(String id, String role) {
        return new SessionMessage(id, "cs-1", "b-1", role,
                role.equals("user") ? "chat_user" : "chat_response",
                "content for " + id, Instant.now(), Map.of());
    }
}
