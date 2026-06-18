package com.gsim.context;

import com.gsim.app.AppConfig;
import com.gsim.branch.BranchAnalyzer;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextSession 加固测试。
 */
@DisplayName("ContextSession Hardening")
class ContextSessionHardeningTest {

    @TempDir
    Path tempDir;
    private DataManager dataManager;
    private ContextSessionManager sessionManager;
    private ContextSessionStore sessionStore;

    @BeforeEach
    void setUp() throws Exception {
        AppConfig config = AppConfig.forTesting();
        Path dataRoot = tempDir.resolve("data");
        java.nio.file.Files.createDirectories(dataRoot);

        dataManager = new DataManager(dataRoot);

        BranchMessageStore messageStore = new BranchMessageStore(dataManager, dataRoot);
        BranchAnalyzer branchAnalyzer = new BranchAnalyzer(dataManager, messageStore, null);

        Path worldDir = dataRoot.resolve("worlds").resolve("default");
        var summaryStore = new NodeSummaryStore(worldDir);
        var pinStore = new PinnedConstraintStore(worldDir);
        var pathRenderer = new BranchPathSummaryRenderer(dataManager, summaryStore);
        sessionStore = new ContextSessionStore(worldDir);

        var renderer = new BranchContextRenderer(dataManager, dataRoot, messageStore,
                branchAnalyzer, pathRenderer, summaryStore, pinStore);

        sessionManager = new ContextSessionManager(sessionStore, renderer, dataManager, worldDir);
    }

    @Test
    @DisplayName("两个 apiSessionId 不共享 active ContextSession")
    void twoApiSessionsShouldNotShareContextSession() {
        ContextSession s1 = sessionManager.getOrCreateActiveSession("api-session-a", "branch.b0000-start");
        ContextSession s2 = sessionManager.getOrCreateActiveSession("api-session-b", "branch.b0000-start");

        assertNotEquals(s1.sessionId(), s2.sessionId());
        assertNotEquals(s1.apiSessionId(), s2.apiSessionId());
        assertEquals("api-session-a", s1.apiSessionId());
        assertEquals("api-session-b", s2.apiSessionId());

        // 两个 session 都应活跃
        assertTrue(sessionStore.findActiveByApiSessionId("api-session-a").isPresent());
        assertTrue(sessionStore.findActiveByApiSessionId("api-session-b").isPresent());
    }

    @Test
    @DisplayName("同 session 第二次 renderForLlm 不生成新 baseContextId")
    void secondRenderShouldNotCreateNewBaseContext() {
        ContextSession s = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        String firstBaseContextId = s.baseContextId();

        // 第一次 renderForLlm
        String rendered1 = sessionManager.renderForLlm("test", "input1");
        assertNotNull(rendered1);

        // 第二次 renderForLlm — 不应改变 baseContextId
        String rendered2 = sessionManager.renderForLlm("test", "input2");
        assertNotNull(rendered2);

        // 验证 baseContextId 不变
        ContextSession after = sessionManager.getActiveSession("test").orElseThrow();
        assertEquals(firstBaseContextId, after.baseContextId());

        // 验证没有重复创建多个 baseContext 文件
        Path contextDir = dataManager.getDataRoot().resolve("worlds").resolve("default")
                .resolve("context").resolve("base_contexts");
        java.io.File[] files = contextDir.toFile().listFiles((d, name) -> name.endsWith(".md"));
        assertNotNull(files);
        assertEquals(1, files.length, "Should have only one base context file");
    }

    @Test
    @DisplayName("appendMessage 后 renderForLlm 包含 session message")
    void renderForLlmShouldContainAppendedMessage() {
        sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        ContextSession s = sessionManager.getActiveSession("test").orElseThrow();

        // 追加消息
        SessionMessage msg = SessionMessage.user(s.sessionId(), "branch.b0000-start", "玩家向北方派出侦察队");
        sessionManager.appendMessage(s.sessionId(), msg);

        // 渲染应包含该消息
        String rendered = sessionManager.renderForLlm("test", "继续推演");
        assertNotNull(rendered);
        assertTrue(rendered.contains("玩家向北方派出侦察队") || rendered.contains("Session Messages"));
    }

    @Test
    @DisplayName("/context reset 后 baseContextId 改变")
    void resetShouldChangeBaseContextId() {
        ContextSession s1 = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        String base1 = s1.baseContextId();

        ContextSession s2 = sessionManager.resetSession("test", "test reset");
        String base2 = s2.baseContextId();

        assertNotEquals(base1, base2);
        assertNotEquals(s1.sessionId(), s2.sessionId());
    }

    @Test
    @DisplayName("/api/context/base 不包含 session messages")
    void baseContextShouldNotContainSessionMessages() {
        sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        ContextSession s = sessionManager.getActiveSession("test").orElseThrow();

        // 追加消息
        sessionManager.appendMessage(s.sessionId(),
                SessionMessage.user(s.sessionId(), "branch.b0000-start", "测试消息内容"));

        // 获取 base context markdown
        String baseMd = sessionManager.getBaseContextMarkdown("test");
        assertNotNull(baseMd);
        // base context 不应包含 session messages
        assertFalse(baseMd.contains("测试消息内容"),
                "BaseContext should NOT contain session messages");
        assertFalse(baseMd.contains("Session Messages"),
                "BaseContext should NOT contain Session Messages section");
    }

    @Test
    @DisplayName("/api/context/rendered (renderForLlm) 包含 session messages")
    void renderedContextShouldContainSessionMessages() {
        sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        ContextSession s = sessionManager.getActiveSession("test").orElseThrow();

        // 追加消息
        sessionManager.appendMessage(s.sessionId(),
                SessionMessage.user(s.sessionId(), "branch.b0000-start", "渲染测试消息"));

        // renderForLlm 应包含 session messages
        String rendered = sessionManager.renderForLlm("test", "新输入");
        assertNotNull(rendered);
        boolean hasMsg = rendered.contains("渲染测试消息") || rendered.contains("Session Messages");
        assertTrue(hasMsg, "renderForLlm should include session messages");
    }

    @Test
    @DisplayName("reset 后旧 session 不再活跃")
    void resetShouldCloseOldSession() {
        ContextSession s1 = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        String oldId = s1.sessionId();

        sessionManager.resetSession("test", "manual reset");

        // 旧 session 不再活跃
        Optional<ContextSession> old = sessionStore.findById(oldId);
        assertTrue(old.isPresent());
        assertNotEquals(ContextSessionStatus.ACTIVE, old.get().status());
    }
}
