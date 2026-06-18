package com.gsim.agent;

import com.gsim.chat.BranchMessageStore;
import com.gsim.chat.NodeAgentChatService;
import com.gsim.app.ApplicationContext;
import com.gsim.app.AppConfig;
import com.gsim.context.BranchContextRenderer;
import com.gsim.context.session.ContextSessionManager;
import com.gsim.context.session.ContextSessionStore;
import com.gsim.data.DataManager;
import com.gsim.llm.FakeLlmClient;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies CLI control character inputs are sanitized before entering Agent.
 */
class CliControlCharInputTest {

    private FakeLlmClient fakeLlm;
    private NodeAgentChatService chatService;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        Path dataRoot = tmp.resolve("data");
        DataManager dm = new DataManager(dataRoot);
        dm.init();

        fakeLlm = new FakeLlmClient();
        fakeLlm.setNextResponse("正常回复。");
        ToolRegistry tools = new ToolRegistry();
        OrchestratorAgent orch = new OrchestratorAgent(fakeLlm, tools, "test-model");

        BranchMessageStore msgStore = new BranchMessageStore(dm, dataRoot);
        BranchContextRenderer renderer = new BranchContextRenderer(dm, dataRoot, msgStore, null);

        Path worldDir = dataRoot.resolve("worlds").resolve(dm.getActiveRootId());
        ContextSessionStore sessionStore = new ContextSessionStore(worldDir);
        ContextSessionManager ctxSessionMgr = new ContextSessionManager(sessionStore, renderer, dm, worldDir);

        AppConfig config = AppConfig.forTesting();
        ApplicationContext appCtx = new ApplicationContext(config);
        appCtx.setDataManager(dm);

        chatService = new NodeAgentChatService(dm, renderer, orch, ctxSessionMgr, dataRoot, appCtx);
    }

    @Test
    void ansiControlOnlyInputIsIgnored() throws Exception {
        String input = "[A[B";
        String response = chatService.chat(input);
        // Should return empty (ignored), not call LLM
        assertTrue(response.isEmpty(), "Control-char-only input should be ignored: " + response);
    }

    @Test
    void inputWithAnsiCodesIsCleaned() throws Exception {
        fakeLlm.setNextResponse("理解你的问题。");
        String input = "[31mRed text[0m 你好世界";
        String response = chatService.chat(input);
        assertTrue(response.contains("理解"), "Cleaned input should reach LLM: " + response);
    }

    @Test
    void normalInputStillWorks() throws Exception {
        String response = chatService.chat("正常的测试输入");
        assertTrue(response.contains("正常回复"), "Normal input should work: " + response);
    }
}
