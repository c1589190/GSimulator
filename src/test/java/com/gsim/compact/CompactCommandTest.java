package com.gsim.compact;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.app.AppConfig;
import com.gsim.branch.BranchAnalyzer;
import com.gsim.chat.BranchMessageStore;
import com.gsim.context.BranchContextRenderer;
import com.gsim.context.memory.PinnedConstraintStore;
import com.gsim.context.session.ContextSession;
import com.gsim.context.session.ContextSessionManager;
import com.gsim.context.session.ContextSessionStore;
import com.gsim.context.session.SessionMessage;
import com.gsim.context.summary.BranchPathSummaryRenderer;
import com.gsim.context.summary.NodeSummaryStore;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.interaction.commands.CompactCommand;
import com.gsim.llm.FakeLlmManager;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 CompactCommand 的 /compact 命令行为。
 */
@DisplayName("CompactCommand")
class CompactCommandTest {

    @TempDir
    Path tempDir;
    private DataManager dm;
    private ContextSessionManager ctxSessionManager;
    private FakeLlmManager fakeLlm;
    private OrchestratorAgent orchestrator;
    private CompactCommand command;
    private InteractionSession session;

    @BeforeEach
    void setUp() throws Exception {
        Path dataRoot = tempDir.resolve("data");
        java.nio.file.Files.createDirectories(dataRoot);

        dm = com.gsim.TestWorldFactory.createWithDefaultRoot(dataRoot);

        // 渲染基础设施
        BranchMessageStore messageStore = new BranchMessageStore(dm, dataRoot);
        BranchAnalyzer branchAnalyzer = new BranchAnalyzer(dm, messageStore, null);

        Path worldDir = dataRoot.resolve("worlds").resolve("default");
        var summaryStore = new NodeSummaryStore(worldDir);
        var pinStore = new PinnedConstraintStore(worldDir);
        var pathRenderer = new BranchPathSummaryRenderer(dm, summaryStore);
        var sessionStore = new ContextSessionStore(worldDir);

        var renderer = new BranchContextRenderer(dm, dataRoot, messageStore,
                branchAnalyzer, pathRenderer, summaryStore, pinStore);

        ctxSessionManager = new ContextSessionManager(sessionStore, renderer, dm, worldDir);

        // LLM
        fakeLlm = new FakeLlmManager("compact-model");

        // Compactor
        AppConfig config = AppConfig.forTesting();
        ContextCompactor compactor = new ContextCompactor(fakeLlm, config, e -> {});

        // Orchestrator
        orchestrator = new OrchestratorAgent(fakeLlm, new ToolRegistry(), "test-model",
                e -> {}, new com.gsim.agent.ToolPermissionGate() {
                    @Override
                    public com.gsim.agent.ConfirmationChoice askConfirmation(
                            com.gsim.agent.ToolConfirmationRequest request) {
                        return com.gsim.agent.ConfirmationChoice.ALLOW_ONCE;
                    }
                }, new com.gsim.agent.ToolGroupManager());

        command = new CompactCommand(ctxSessionManager, compactor, orchestrator);

        // Session
        var campaignService = new com.gsim.campaign.CampaignService(
                new com.gsim.storage.DataPaths(config), new com.gsim.util.TimeProvider());
        var turnService = new com.gsim.campaign.TurnService(
                new com.gsim.storage.DataPaths(config), new com.gsim.util.TimeProvider());
        var playerActionService = new com.gsim.campaign.PlayerActionService(
                new com.gsim.storage.DataPaths(config), new com.gsim.util.TimeProvider());
        var ctx = new com.gsim.interaction.InteractionContext();
        session = new InteractionSession(ctx, config, campaignService, turnService,
                playerActionService, new ToolRegistry(), null);
    }

    // ---- 无活跃 session ----

    @Test
    @DisplayName("无活跃 session 时提示无需压缩")
    void noActiveSession_showsHint() {
        InteractionResult r = command.execute(new String[]{}, session);
        assertTrue(r.success());
        assertTrue(r.displayText().contains("无需压缩") || r.displayText().contains("活跃的对话上下文"),
                "Should hint that no active session exists: " + r.displayText());
    }

    // ---- 消息太少 ----

    @Test
    @DisplayName("消息数 < 5 时提示无需压缩")
    void tooFewMessages_showsHint() {
        // 创建 session 并添加少量消息
        ContextSession cs = ctxSessionManager.createSession("default", "branch.b0000-start", "branch.b0000-start");
        ctxSessionManager.appendMessage(cs.sessionId(),
                SessionMessage.user(cs.sessionId(), cs.branchId(), "Hello"));
        ctxSessionManager.appendMessage(cs.sessionId(),
                SessionMessage.assistant(cs.sessionId(), cs.branchId(), "Hi there"));

        InteractionResult r = command.execute(new String[]{}, session);
        assertTrue(r.success());
        assertTrue(r.displayText().contains("无需压缩") || r.displayText().contains("尚短"),
                "Should hint that messages are too few: " + r.displayText());
    }

    // ---- 正常压缩 ----

    @Test
    @DisplayName("有足够消息时执行压缩并返回新 session 摘要")
    void sufficientMessages_compactsAndReturnsSummary() {
        // Queue LLM response for compaction
        fakeLlm.addResponse("## 对话摘要\n- 用户意图: 测试\n- 关键操作: 多次对话\n- 主要结果: 测试通过");

        // 创建 session 并添加足够消息（≥ 5）
        ContextSession cs = ctxSessionManager.createSession("default", "branch.b0000-start", "branch.b0000-start");
        for (int i = 0; i < 6; i++) {
            ctxSessionManager.appendMessage(cs.sessionId(),
                    SessionMessage.user(cs.sessionId(), cs.branchId(), "Message " + i));
            ctxSessionManager.appendMessage(cs.sessionId(),
                    SessionMessage.assistant(cs.sessionId(), cs.branchId(), "Response " + i));
        }

        InteractionResult r = command.execute(new String[]{}, session);
        assertTrue(r.success(), "Compaction should succeed: " + r.displayText());
        assertTrue(r.displayText().contains("已压缩") || r.displayText().contains("压缩"),
                "Should indicate compaction completed: " + r.displayText());
        assertTrue(r.displayText().contains("摘要"),
                "Should mention summary: " + r.displayText());
    }

    // ---- 压缩后 session 已重置 ----

    @Test
    @DisplayName("压缩后旧 session 关闭、新 session 创建")
    void afterCompact_sessionIsReset() {
        fakeLlm.addResponse("## 对话摘要\n- 用户意图: 历史测试\n- 关键操作: 6 轮对话\n- 主要结果: 通过");

        ContextSession cs = ctxSessionManager.createSession("default", "branch.b0000-start", "branch.b0000-start");
        String oldSessionId = cs.sessionId();

        for (int i = 0; i < 6; i++) {
            ctxSessionManager.appendMessage(oldSessionId,
                    SessionMessage.user(oldSessionId, cs.branchId(), "Message " + i));
            ctxSessionManager.appendMessage(oldSessionId,
                    SessionMessage.assistant(oldSessionId, cs.branchId(), "Response " + i));
        }

        command.execute(new String[]{}, session);

        // 旧 session 应该已关闭
        assertTrue(ctxSessionManager.getActiveSession("default").isPresent(),
                "A new active session should exist");
        String newSessionId = ctxSessionManager.getActiveSession("default").get().sessionId();
        assertNotEquals(oldSessionId, newSessionId, "Session ID should change after compact");

        // 新 session 应该有 system_note 消息
        List<SessionMessage> newMsgs = ctxSessionManager.getSessionMessages(newSessionId);
        boolean hasSummaryNote = newMsgs.stream()
                .anyMatch(m -> "system_note".equals(m.type()) && m.content().contains("上下文摘要"));
        assertTrue(hasSummaryNote, "New session should contain context summary note");
    }

    // ---- LLM 压缩失败 ----

    @Test
    @DisplayName("LLM 返回空摘要时提示失败")
    void llmReturnsEmpty_showsFailure() {
        // 不预填任何响应 → FakeLlmManager 返回 failure（空 content）
        // 不放任何成功响应，submit() 默认返回 failure

        ContextSession cs = ctxSessionManager.createSession("default", "branch.b0000-start", "branch.b0000-start");
        for (int i = 0; i < 6; i++) {
            ctxSessionManager.appendMessage(cs.sessionId(),
                    SessionMessage.user(cs.sessionId(), cs.branchId(), "Message " + i));
        }

        InteractionResult r = command.execute(new String[]{}, session);
        assertFalse(r.success(), "Should fail when LLM returns empty summary");
        assertTrue(r.displayText().contains("失败"), "Should indicate failure: " + r.displayText());
    }

    // ---- 命令元数据 ----

    @Test
    @DisplayName("命令名称和描述")
    void commandMetadata() {
        assertEquals("compact", command.name());
        assertNotNull(command.description());
        assertTrue(command.description().contains("压缩"));
        assertEquals("/compact", command.usage());
    }
}
