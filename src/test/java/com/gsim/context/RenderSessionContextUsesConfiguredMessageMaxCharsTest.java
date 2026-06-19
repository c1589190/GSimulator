package com.gsim.context;

import com.gsim.context.session.SessionMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 renderSessionContext 按配置的 messageMaxChars 截断单条消息。
 */
@DisplayName("RenderSessionContext — 可配置单条消息最大字符数")
class RenderSessionContextUsesConfiguredMessageMaxCharsTest {

    private static final String BASE_CONTEXT = "# Base Context\n\nTest world.";

    private static SessionMessage userMsg(String id, String content) {
        return new SessionMessage(id, "cs-1", "b-1", "user", "chat_user",
                content, Instant.now(), Map.of());
    }

    @Test
    @DisplayName("messageMaxChars=100 时长消息被截断并加 '...' 后缀")
    void longMessageTruncatedAt100() {
        String longContent = "A".repeat(500);
        SessionMessage msg = userMsg("u-1", longContent);

        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, List.of(msg), "input",
                        12, 100);

        // 截断后应为 97 chars 内容 + "..."
        assertTrue(result.contains("A".repeat(97) + "..."),
                "Long message should be truncated to 97 + '...'");
        assertFalse(result.contains("A".repeat(100)),
                "Full 100 A's should not appear (would mean no truncation suffix)");
    }

    @Test
    @DisplayName("messageMaxChars 大于消息长度时不截断")
    void shortMessageNotTruncated() {
        String shortContent = "Hello, this is a short message.";
        SessionMessage msg = userMsg("u-1", shortContent);

        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, List.of(msg), "input",
                        12, 4000);

        assertTrue(result.contains(shortContent),
                "Short message should appear in full");
        assertFalse(result.contains(shortContent + "..."),
                "Should not have '...' suffix on un-truncated message");
    }

    @Test
    @DisplayName("messageMaxChars=500（最小值）时生效")
    void minMessageMaxCharsWorks() {
        String longContent = "B".repeat(2000);
        SessionMessage msg = userMsg("u-1", longContent);

        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, List.of(msg), "input",
                        12, 500);

        assertTrue(result.contains("B".repeat(497) + "..."));
        assertFalse(result.contains("B".repeat(500)));
    }

    @Test
    @DisplayName("messageMaxChars=20000（最大值）时几乎不截断")
    void maxMessageMaxCharsWorks() {
        String content = "C".repeat(15000);
        SessionMessage msg = userMsg("u-1", content);

        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, List.of(msg), "input",
                        12, 20000);

        // 15000 < 20000，不应截断
        assertTrue(result.contains(content));
    }

    @Test
    @DisplayName("多条消息各自独立截断")
    void multipleMessagesTruncatedIndependently() {
        String long1 = "X".repeat(300);
        String long2 = "Y".repeat(300);
        SessionMessage msg1 = userMsg("u-1", long1);
        SessionMessage msg2 = userMsg("u-2", long2);

        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, List.of(msg1, msg2), "input",
                        12, 100);

        assertTrue(result.contains("X".repeat(97) + "..."));
        assertTrue(result.contains("Y".repeat(97) + "..."));
    }

    @Test
    @DisplayName("恰好等于 maxChars 的消息不截断（无需 ...）")
    void messageExactlyAtMaxCharsNotTruncated() {
        String exactContent = "Z".repeat(100);
        SessionMessage msg = userMsg("u-1", exactContent);

        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, List.of(msg), "input",
                        12, 100);

        // 100 chars ≤ 100 maxChars → 不截断
        assertTrue(result.contains(exactContent));
        assertFalse(result.contains("..."));
    }

    @Test
    @DisplayName("messageMaxChars - 3 恰好为 0 或负时仍安全（短 maxChars）")
    void veryShortMaxCharsStillSafe() {
        String content = "Hello";
        SessionMessage msg = userMsg("u-1", content);

        // messageMaxChars=3 → substring(0, 0) + "..." = "..."
        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, List.of(msg), "input",
                        12, 3);

        // content.length=5 > 3, 所以截断 → substring(0,0) + "..." = "..."
        assertTrue(result.contains("[user] ..."));
    }

    @Test
    @DisplayName("旧版 3-param API 使用默认值（2000）")
    void oldApiUsesDefault2000() {
        String longContent = "D".repeat(3000);
        SessionMessage msg = userMsg("u-1", longContent);

        String result = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext(BASE_CONTEXT, List.of(msg), "input");

        // 旧版默认 messageMaxChars=2000
        assertTrue(result.contains("D".repeat(1997) + "..."));
        assertFalse(result.contains("D".repeat(2000)));
    }
}
