package com.gsim.chat;

import com.gsim.data.DataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BranchMessageStore")
class BranchMessageStoreTest {
    @TempDir Path tempDir;
    private DataManager dm;
    private BranchMessageStore store;

    @BeforeEach
    void setUp() throws Exception {
        dm = new DataManager(tempDir);
        store = new BranchMessageStore(dm, tempDir);
    }

    @Test
    @DisplayName("appendMessage 遇到污染内容不应原样写入")
    void testAppendMessage_PollutedContentFiltered() throws Exception {
        String branchId = dm.getActiveBranch();

        String pollutedContent = """
                * test: Run tests with the given coverage strategy. Use when asked to run tests, check coverage, or verify test results. (project) - mvn test with optional coverage (JaCoCo) and reporting (Surefire)
                * wiki_search: Search local PRTS Wiki text files""";

        BranchMessage pollutedMsg = BranchMessage.create("m9999", "assistant", "chat_response", pollutedContent);
        store.appendMessage(branchId, pollutedMsg);

        // 读取 branch 文件，不应包含污染内容
        List<BranchMessage> msgs = store.listMessages(branchId);
        assertFalse(msgs.isEmpty());

        // 找到我们写入的消息
        BranchMessage written = msgs.stream()
                .filter(m -> "m9999".equals(m.id()))
                .findFirst()
                .orElse(null);
        assertNotNull(written);
        // 应该是 system_note 而不是原内容
        assertEquals("system_note", written.type());
        assertTrue(written.content().contains("工具定义污染已被过滤"));
        assertFalse(written.content().contains("Run tests with the given coverage strategy"));
    }

    @Test
    @DisplayName("正常 tool_call message 正常写入")
    void testAppendMessage_NormalToolCall() throws Exception {
        String branchId = dm.getActiveBranch();

        BranchMessage tcMsg = BranchMessage.tool("m0001", "tool_call", "wiki_search",
                "{query=罗德岛, limit=5}");
        store.appendMessage(branchId, tcMsg);

        List<BranchMessage> msgs = store.listMessages(branchId);
        BranchMessage written = msgs.stream()
                .filter(m -> "m0001".equals(m.id()))
                .findFirst()
                .orElse(null);
        assertNotNull(written);
        assertEquals("tool_call", written.type());
        assertEquals("wiki_search", written.toolName());
        assertTrue(written.content().contains("罗德岛"));
    }

    @Test
    @DisplayName("正常 tool_result message 正常写入")
    void testAppendMessage_NormalToolResult() throws Exception {
        String branchId = dm.getActiveBranch();

        BranchMessage trMsg = BranchMessage.tool("m0002", "tool_result", "wiki_search",
                "工具 wiki_search 返回:\n- 罗德岛 (rhodes-island.txt)\n  罗德岛是泰拉大陆主要的感染者救助组织。");
        store.appendMessage(branchId, trMsg);

        List<BranchMessage> msgs = store.listMessages(branchId);
        BranchMessage written = msgs.stream()
                .filter(m -> "m0002".equals(m.id()))
                .findFirst()
                .orElse(null);
        assertNotNull(written);
        assertEquals("tool_result", written.type());
        assertTrue(written.content().contains("罗德岛"));
    }

    @Test
    @DisplayName("正常 chat_user message 正常写入")
    void testAppendMessage_NormalChatUser() throws Exception {
        String branchId = dm.getActiveBranch();

        BranchMessage msg = BranchMessage.create("m0003", "user", "chat_user", "调查罗德岛");
        store.appendMessage(branchId, msg);

        List<BranchMessage> msgs = store.listMessages(branchId);
        BranchMessage written = msgs.stream()
                .filter(m -> "m0003".equals(m.id()))
                .findFirst()
                .orElse(null);
        assertNotNull(written);
        assertEquals("chat_user", written.type());
        assertEquals("调查罗德岛", written.content());
    }

    @Test
    @DisplayName("nextMessageId 从已有消息中正确递增")
    void testNextMessageId() throws Exception {
        String branchId = dm.getActiveBranch();

        store.appendMessage(branchId, BranchMessage.create("m0001", "user", "chat_user", "hello"));
        store.appendMessage(branchId, BranchMessage.create("m0002", "assistant", "chat_response", "hi"));

        assertEquals("m0003", store.nextMessageId(branchId));
    }

    @Test
    @DisplayName("listMessages 兼容旧格式 ### user / ### assistant")
    void testListMessages_LegacyFormat() throws Exception {
        // 创建一个带旧格式 LLM 上下文记录的 branch 文件
        String branchId = "branch.b0000-start";
        Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0000-start.md");
        Files.createDirectories(branchFile.getParent());
        Files.writeString(branchFile, """
                id: branch.b0000-start
                type: branch
                name: 时间原点
                parent: none
                turn: 0
                world_time: 时间原点
                status: resolved
                tags: [时间节点]
                updated: 2026-06-18
                -------------------

                # 时间原点

                ## 一、本节点输入
                世界初始化。

                ## 二、LLM 上下文记录

                ### user

                调查罗德岛

                ### assistant

                罗德岛具备军事价值。

                ## 三、推演结果
                无。
                """);

        // 需要 reload 让 DataManager 加载
        dm = new DataManager(tempDir);
        store = new BranchMessageStore(dm, tempDir);

        List<BranchMessage> msgs = store.listMessages(branchId);
        // 旧格式解析会创建 message blocks
        assertFalse(msgs.isEmpty());
        assertTrue(msgs.stream().anyMatch(m -> "调查罗德岛".equals(m.content())));
        assertTrue(msgs.stream().anyMatch(m -> "罗德岛具备军事价值。".equals(m.content())));
    }
}
