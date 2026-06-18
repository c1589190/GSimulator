package com.gsim.interaction;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.app.AppConfig;
import com.gsim.branch.BranchAnalyzer;
import com.gsim.chat.BranchMessageStore;
import com.gsim.chat.NodeAgentChatService;
import com.gsim.context.BranchContextRenderer;
import com.gsim.context.memory.PinnedConstraintStore;
import com.gsim.context.session.ContextSessionManager;
import com.gsim.context.session.ContextSessionStore;
import com.gsim.context.summary.BranchPathSummaryRenderer;
import com.gsim.context.summary.NodeSummaryStore;
import com.gsim.data.DataManager;
import com.gsim.interaction.commands.*;
import com.gsim.llm.FakeLlmClient;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一 Agent 入口测试 — 不调用真实 LLM。
 */
@DisplayName("Unified Agent Entry")
class UnifiedAgentEntryTest {

    @TempDir
    Path tempDir;

    private FakeLlmClient fakeLlm;
    private InteractionSession session;
    private NodeAgentChatService chatService;

    @BeforeEach
    void setUp() throws Exception {
        fakeLlm = new FakeLlmClient();
        fakeLlm.setNextResponse("测试推演回复内容。");

        AppConfig config = AppConfig.forTesting();
        Path dataRoot = tempDir.resolve("data");
        java.nio.file.Files.createDirectories(dataRoot);

        DataManager dm = com.gsim.TestWorldFactory.createWithDefaultRoot(dataRoot);
        BranchMessageStore messageStore = new BranchMessageStore(dm, dataRoot);
        BranchAnalyzer branchAnalyzer = new BranchAnalyzer(dm, messageStore, null);

        Path worldDir = dataRoot.resolve("worlds").resolve("default");
        var summaryStore = new NodeSummaryStore(worldDir);
        var pinStore = new PinnedConstraintStore(worldDir);
        var pathRenderer = new BranchPathSummaryRenderer(dm, summaryStore);
        var sessionStore = new ContextSessionStore(worldDir);

        BranchContextRenderer renderer = new BranchContextRenderer(dm, dataRoot, messageStore,
                branchAnalyzer, pathRenderer, summaryStore, pinStore);

        ContextSessionManager ctxSessionManager = new ContextSessionManager(
                sessionStore, renderer, dm, worldDir);

        OrchestratorAgent orchestrator = new OrchestratorAgent(
                fakeLlm, new ToolRegistry(), "test-model");

        chatService = new NodeAgentChatService(dm, renderer, orchestrator, ctxSessionManager,
                dm.getDataRoot(), null);
    }

    @Test
    @DisplayName("/sim 空内容应返回废弃提示")
    void simEmptyContentReturnsDeprecationMessage() {
        SimCommand sim = new SimCommand(chatService);
        InteractionResult result = sim.execute(new String[]{}, null);
        assertFalse(result.success());
        assertTrue(result.message().contains("已废弃"));
        assertTrue(result.message().contains("自然语言"));
    }

    @Test
    @DisplayName("/run 空内容应返回废弃提示")
    void runEmptyContentReturnsDeprecationMessage() {
        RunCommand run = new RunCommand(chatService);
        InteractionResult result = run.execute(new String[]{}, null);
        assertFalse(result.success());
        assertTrue(result.message().contains("已废弃"));
    }

    @Test
    @DisplayName("/sim 描述中包含已废弃标识")
    void simDescriptionIndicatesDeprecation() {
        SimCommand sim = new SimCommand(null);
        assertTrue(sim.description().contains("已废弃"));
    }

    @Test
    @DisplayName("/run 描述中包含已废弃标识")
    void runDescriptionIndicatesDeprecation() {
        RunCommand run = new RunCommand(null);
        assertTrue(run.description().contains("已废弃"));
    }

    @Test
    @DisplayName("/chat 描述为统一入口并提及废弃")
    void chatDescriptionIsUnifiedEntry() {
        ChatCommand chat = new ChatCommand(null);
        assertTrue(chat.description().contains("统一"));
        assertTrue(chat.description().contains("已废弃"));
    }

    @Test
    @DisplayName("/sim 有内容时转发到 chatService.chat()")
    void simWithContentForwardsToChatService() throws Exception {
        fakeLlm.setNextResponse("推演回复");

        SimCommand sim = new SimCommand(chatService);
        InteractionResult result = sim.execute(new String[]{"推演测试内容"}, null);

        assertTrue(result.success());
        assertTrue(result.message().contains("sim → chat"));
    }

    @Test
    @DisplayName("/run 有内容时转发到 chatService.chat()")
    void runWithContentForwardsToChatService() throws Exception {
        fakeLlm.setNextResponse("结算回复");

        RunCommand run = new RunCommand(chatService);
        InteractionResult result = run.execute(new String[]{"结算测试内容"}, null);

        assertTrue(result.success());
        assertTrue(result.message().contains("run → chat"));
    }
}
