package com.gsim.context;

import com.gsim.app.AppConfig;
import com.gsim.branch.BranchAnalyzer;
import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.context.memory.PinnedConstraintStore;
import com.gsim.context.session.*;
import com.gsim.context.summary.BranchPathSummaryRenderer;
import com.gsim.context.summary.NodeSummaryStore;
import com.gsim.data.DataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextSession 主流程测试 — 不调用真实 LLM。
 */
@DisplayName("ContextSession Main Flow")
class ContextSessionMainFlowTest {

    @TempDir
    Path tempDir;
    private DataManager dataManager;
    private ContextSessionManager sessionManager;
    private BranchMessageStore branchMessageStore;

    @BeforeEach
    void setUp() throws Exception {
        AppConfig config = AppConfig.forTesting();
        Path dataRoot = tempDir.resolve("data");
        java.nio.file.Files.createDirectories(dataRoot);

        dataManager = com.gsim.TestWorldFactory.createWithDefaultRoot(dataRoot);
        branchMessageStore = new BranchMessageStore(dataManager, dataRoot);
        BranchAnalyzer branchAnalyzer = new BranchAnalyzer(dataManager, branchMessageStore, null);

        Path worldDir = dataRoot.resolve("worlds").resolve("default");
        var summaryStore = new NodeSummaryStore(worldDir);
        var pinStore = new PinnedConstraintStore(worldDir);
        var pathRenderer = new BranchPathSummaryRenderer(dataManager, summaryStore);
        var sessionStore = new ContextSessionStore(worldDir);

        var renderer = new BranchContextRenderer(dataManager, dataRoot, branchMessageStore,
                branchAnalyzer, pathRenderer, summaryStore, pinStore);

        sessionManager = new ContextSessionManager(sessionStore, renderer, dataManager, worldDir);
    }

    @Test
    @DisplayName("第一轮请求创建 ContextSession")
    void firstRoundCreatesContextSession() {
        ContextSession cs = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        assertNotNull(cs);
        assertNotNull(cs.sessionId());
        assertNotNull(cs.baseContextId());
        assertTrue(cs.isActive());

        // BaseContext 文件应存在
        Path bcFile = dataManager.getDataRoot().resolve("worlds").resolve("default")
                .resolve("context").resolve("base_contexts").resolve(cs.baseContextId() + ".md");
        assertTrue(java.nio.file.Files.exists(bcFile));
    }

    @Test
    @DisplayName("第二轮请求复用同一个 baseContextId")
    void secondRoundReusesBaseContextId() {
        ContextSession cs1 = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        String base1 = cs1.baseContextId();

        // 模拟一轮对话：追加消息
        sessionManager.appendMessage(cs1.sessionId(),
                SessionMessage.user(cs1.sessionId(), "branch.b0000-start", "测试消息"));
        sessionManager.appendMessage(cs1.sessionId(),
                SessionMessage.assistant(cs1.sessionId(), "branch.b0000-start", "回复消息"));

        // 第二轮请求
        ContextSession cs2 = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        String base2 = cs2.baseContextId();

        // 同一个 ContextSession，baseContextId 不变
        assertEquals(base1, base2);
        assertEquals(cs1.sessionId(), cs2.sessionId());
    }

    @Test
    @DisplayName("renderForLlm 包含 session messages")
    void renderForLlmContainsSessionMessages() {
        ContextSession cs = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        sessionManager.appendMessage(cs.sessionId(),
                SessionMessage.user(cs.sessionId(), "branch.b0000-start", "玩家派出侦察队"));
        sessionManager.appendMessage(cs.sessionId(),
                SessionMessage.assistant(cs.sessionId(), "branch.b0000-start", "侦察队发现了敌人营地"));

        String rendered = sessionManager.renderForLlm("test", "继续推演");
        assertNotNull(rendered);
        assertTrue(rendered.contains("玩家派出侦察队") || rendered.contains("Session Messages"));
    }

    @Test
    @DisplayName("BranchMessageStore 仍可独立写入和读取")
    void branchMessageStoreStillWorks() throws Exception {
        String branchId = "branch.b0000-start";

        // 通过 BranchMessageStore 写入（模拟旧路径兼容）
        String msgId = branchMessageStore.nextMessageId(branchId);
        BranchMessage bm = BranchMessage.create(msgId, "user", "chat_user", "历史消息");
        branchMessageStore.appendMessage(branchId, bm);

        List<BranchMessage> msgs = branchMessageStore.listMessages(branchId);
        assertFalse(msgs.isEmpty());
        assertEquals("历史消息", msgs.get(0).content());
    }

    @Test
    @DisplayName("appendMessage 后消息可在 SessionMessageStore 中读取")
    void appendedMessageIsReadable() {
        ContextSession cs = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        SessionMessage msg = SessionMessage.user(cs.sessionId(), "branch.b0000-start", "测试输入");
        sessionManager.appendMessage(cs.sessionId(), msg);

        List<SessionMessage> msgs = sessionManager.getSessionMessages(cs.sessionId());
        assertEquals(1, msgs.size());
        assertEquals("测试输入", msgs.get(0).content());
    }

    @Test
    @DisplayName("ContextBudget 压缩超过预算的消息")
    void contextBudgetCompressesOldMessages() {
        ContextBudget budget = new ContextBudget(5000, 3, 1000, 2000);

        List<SessionMessage> msgs = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            msgs.add(SessionMessage.user("sid", "branch.b0000-start", "消息内容 " + i));
        }

        ContextBudget.CompressedResult result = budget.compress(msgs);
        // 压缩后应保留最近 3 条 + 1 条 summary
        assertTrue(result.compressedMessages().size() <= 4);
    }

    @Test
    @DisplayName("超长 tool_result 被截断")
    void longToolResultIsTruncated() {
        ContextBudget budget = new ContextBudget();

        String longContent = "x".repeat(5000);
        SessionMessage toolResult = new SessionMessage(
                "msg-1", "cs-1", "branch.b0000-start",
                "tool", "tool_result", longContent, Instant.now(),
                Map.of("fullRef", "branch.b0000-start/output")
        );

        List<SessionMessage> msgs = List.of(toolResult);
        ContextBudget.CompressedResult result = budget.compress(msgs);
        SessionMessage processed = result.compressedMessages().get(0);

        assertTrue(processed.content().length() < longContent.length());
        assertTrue(processed.content().endsWith("..."));
        assertEquals(1, result.truncatedCount());
    }

    @Test
    @DisplayName("ContextBudget 不截断短消息")
    void shortMessageIsNotTruncated() {
        ContextBudget budget = new ContextBudget();
        SessionMessage shortMsg = SessionMessage.user("sid", "branch.b0000-start", "短消息");

        List<SessionMessage> msgs = List.of(shortMsg);
        ContextBudget.CompressedResult result = budget.compress(msgs);

        assertEquals("短消息", result.compressedMessages().get(0).content());
        assertEquals(0, result.truncatedCount());
    }
}
