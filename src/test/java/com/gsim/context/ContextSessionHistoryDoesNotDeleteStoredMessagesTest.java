package com.gsim.context;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.context.session.SessionMessage;
import com.gsim.context.session.SessionMessageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证上下文历史截断只影响 LLM 渲染，不删除磁盘存储的消息。
 *
 * <p>核心原则：filterByTurns / renderSessionContext 是只读过滤操作，
 * 底层 SessionMessageStore 和磁盘文件中的完整消息不受影响。
 */
@DisplayName("ContextSession 历史截断不删除存储消息")
class ContextSessionHistoryDoesNotDeleteStoredMessagesTest {

    @TempDir
    Path tempDir;

    private static SessionMessage userMsg(String id, String content) {
        return new SessionMessage(id, "cs-1", "b-1", "user", "chat_user",
                content, Instant.now(), Map.of());
    }

    private static SessionMessage assistantMsg(String id, String content) {
        return new SessionMessage(id, "cs-1", "b-1", "assistant", "chat_response",
                content, Instant.now(), Map.of());
    }

    @Test
    @DisplayName("filterByTurns 返回过滤后的列表，不修改原始列表")
    void filterByTurnsDoesNotModifyOriginalList() {
        List<SessionMessage> original = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            original.add(userMsg("u-" + i, "User turn " + i));
            original.add(assistantMsg("a-" + i, "Assistant turn " + i));
        }
        assertEquals(40, original.size());

        // 过滤
        List<SessionMessage> filtered = OrchestratorAgent.filterByTurns(original, 3);
        assertEquals(6, filtered.size(), "filtered should have 3 turns × 2 messages");

        // 原始列表不变
        assertEquals(40, original.size(), "original list must not be modified");
        assertEquals("u-0", original.get(0).id(), "first message still there");
        assertEquals("a-19", original.get(39).id(), "last message still there");
    }

    @Test
    @DisplayName("renderSessionContext 渲染后存储中的消息数不变")
    void renderSessionContextDoesNotReduceStorageCount() throws IOException {
        Path worldDir = tempDir.resolve("worlds").resolve("test-world");
        Files.createDirectories(worldDir);

        String sessionId = "test-session-1";
        SessionMessageStore store = new SessionMessageStore(worldDir, sessionId);

        // 追加 50 条消息（25 轮）
        for (int i = 0; i < 25; i++) {
            store.append(userMsg("u-" + i, "User input for turn " + i));
            store.append(assistantMsg("a-" + i, "Assistant response for turn " + i));
        }
        int countBefore = store.count();
        assertEquals(50, countBefore, "should have 50 messages before rendering");

        // 模拟渲染（用少量 historyTurns）
        List<SessionMessage> allMessages = store.getAll();
        String rendered = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext("# Base", allMessages, "current input",
                        3, 4000);

        // 渲染结果只包含最近 3 轮
        assertNotNull(rendered);
        assertTrue(rendered.contains("User input for turn 22"));
        assertTrue(rendered.contains("User input for turn 24"));
        assertFalse(rendered.contains("User input for turn 0"),
                "turn 0 should not appear in rendered output");

        // 存储中的消息数不变
        int countAfter = store.count();
        assertEquals(countBefore, countAfter,
                "message count in store must not change after rendering");

        // 所有原始消息仍在内存中
        List<SessionMessage> afterMessages = store.getAll();
        assertEquals(50, afterMessages.size());
        assertEquals("u-0", afterMessages.get(0).id());
        assertEquals("a-24", afterMessages.get(49).id());
    }

    @Test
    @DisplayName("磁盘文件中的完整消息不受渲染截断影响")
    void diskFileUnaffectedByRenderingTruncation() throws IOException {
        Path worldDir = tempDir.resolve("worlds").resolve("test-world-2");
        Files.createDirectories(worldDir);

        String sessionId = "test-session-disk";
        SessionMessageStore store = new SessionMessageStore(worldDir, sessionId);

        // 写入消息到磁盘
        store.append(userMsg("disk-u-1", "First user message with substantial content"));
        store.append(assistantMsg("disk-a-1", "First assistant response also quite long"));

        // 确认磁盘文件存在
        Path msgFile = worldDir.resolve("context").resolve("session_messages")
                .resolve(sessionId + ".jsonl");
        assertTrue(Files.exists(msgFile), "JSONL file should exist on disk");

        // 读取磁盘文件行数
        List<String> diskLines = Files.readAllLines(msgFile);
        assertEquals(2, diskLines.size(), "disk should have 2 lines");

        // 渲染（使用严格的截断参数）
        List<SessionMessage> allMessages = store.getAll();
        String rendered = new BranchContextRenderer(null, null, null, null)
                .renderSessionContext("# Base", allMessages, "input",
                        12, 30); // very short maxChars

        assertNotNull(rendered);
        // 渲染输出被截断，但磁盘文件不变
        List<String> diskLinesAfter = Files.readAllLines(msgFile);
        assertEquals(2, diskLinesAfter.size(), "disk file must still have 2 lines");
        assertEquals(diskLines.get(0), diskLinesAfter.get(0),
                "first line unchanged on disk");
        assertEquals(diskLines.get(1), diskLinesAfter.get(1),
                "second line unchanged on disk");
    }

    @Test
    @DisplayName("filterByTurns 是纯函数 — 多次调用结果一致且不改变输入")
    void filterByTurnsIsPureFunction() {
        List<SessionMessage> messages = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(userMsg("u-" + i, "turn " + i));
        }

        // 多次调用结果一致
        List<SessionMessage> r1 = OrchestratorAgent.filterByTurns(messages, 3);
        List<SessionMessage> r2 = OrchestratorAgent.filterByTurns(messages, 3);
        assertEquals(r1.size(), r2.size());
        for (int i = 0; i < r1.size(); i++) {
            assertEquals(r1.get(i).id(), r2.get(i).id());
        }

        // 输入不变
        assertEquals(10, messages.size());
    }

    @Test
    @DisplayName("ContextHistoryConfig.DEFAULT 不变性")
    void contextHistoryConfigDefaultIsStable() {
        OrchestratorAgent.ContextHistoryConfig def = OrchestratorAgent.ContextHistoryConfig.DEFAULT;
        assertEquals(12, def.historyTurns());
        assertEquals(4000, def.messageMaxChars());

        // 多次引用同一个实例
        OrchestratorAgent.ContextHistoryConfig def2 = OrchestratorAgent.ContextHistoryConfig.DEFAULT;
        assertSame(def, def2, "DEFAULT should be a singleton constant");
    }
}
