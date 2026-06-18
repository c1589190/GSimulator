package com.gsim.context;

import com.gsim.app.AppConfig;
import com.gsim.branch.BranchAnalyzer;
import com.gsim.chat.BranchMessageStore;
import com.gsim.context.memory.PinnedConstraintStore;
import com.gsim.context.session.ContextSession;
import com.gsim.context.session.ContextSessionManager;
import com.gsim.context.session.ContextSessionStore;
import com.gsim.context.summary.BranchPathSummaryRenderer;
import com.gsim.context.summary.NodeSummaryStore;
import com.gsim.data.DataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextSessionManager 测试。
 */
@DisplayName("ContextSessionManager")
class ContextSessionManagerTest {

    @TempDir
    Path tempDir;
    private DataManager dataManager;
    private ContextSessionManager sessionManager;

    @BeforeEach
    void setUp() throws Exception {
        AppConfig config = AppConfig.forTesting();
        Path dataRoot = tempDir.resolve("data");
        java.nio.file.Files.createDirectories(dataRoot);

        dataManager = new DataManager(dataRoot);
        // initDefault called automatically by constructor

        BranchMessageStore messageStore = new BranchMessageStore(dataManager, dataRoot);
        BranchAnalyzer branchAnalyzer = new BranchAnalyzer(dataManager, messageStore, null);

        Path worldDir = dataRoot.resolve("worlds").resolve("default");
        var summaryStore = new NodeSummaryStore(worldDir);
        var pinStore = new PinnedConstraintStore(worldDir);
        var pathRenderer = new BranchPathSummaryRenderer(dataManager, summaryStore);
        var sessionStore = new ContextSessionStore(worldDir);

        var renderer = new BranchContextRenderer(dataManager, dataRoot, messageStore,
                branchAnalyzer, pathRenderer, summaryStore, pinStore);

        sessionManager = new ContextSessionManager(sessionStore, renderer, dataManager, worldDir);
    }

    @Test
    @DisplayName("getOrCreateActiveSession 应自动创建 session")
    void shouldAutoCreateSession() {
        ContextSession session = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        assertNotNull(session);
        assertNotNull(session.sessionId());
        assertNotNull(session.baseContextId());
        assertTrue(session.isActive());
    }

    @Test
    @DisplayName("同 session 再次获取应返回相同 session")
    void sameSessionShouldReturnSameSession() {
        ContextSession s1 = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        ContextSession s2 = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        assertEquals(s1.sessionId(), s2.sessionId());
    }

    @Test
    @DisplayName("reset 应关闭旧 session 并创建新 session")
    void resetShouldCreateNewSession() {
        ContextSession s1 = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        ContextSession s2 = sessionManager.resetSession("test", "test reset");

        assertNotNull(s1.sessionId());
        assertNotNull(s2.sessionId());
        assertNotEquals(s1.sessionId(), s2.sessionId());

        Optional<ContextSession> active = sessionManager.getActiveSession("test");
        assertTrue(active.isPresent());
        assertEquals(s2.sessionId(), active.get().sessionId());
    }

    @Test
    @DisplayName("close 应标记 CLOSED")
    void closeShouldMarkClosed() {
        ContextSession s1 = sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        sessionManager.closeSession(s1.sessionId(), "done");

        Optional<ContextSession> active = sessionManager.getActiveSession("test");
        assertTrue(active.isEmpty());
    }

    @Test
    @DisplayName("renderForLlm 应返回非空内容")
    void renderForLlmShouldReturnContent() {
        sessionManager.getOrCreateActiveSession("test", "branch.b0000-start");
        String content = sessionManager.renderForLlm("test", "测试输入");
        assertNotNull(content);
        assertFalse(content.isBlank());
        assertTrue(content.contains("Base Context") || content.contains("Branch") || content.contains("GSimulator"));
    }
}
