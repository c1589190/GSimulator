package com.gsim.chat;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.app.ApplicationContext;
import com.gsim.context.BranchContextRenderer;
import com.gsim.context.session.ContextSession;
import com.gsim.context.session.ContextSessionManager;
import com.gsim.context.session.SessionMessage;
import com.gsim.data.DataManager;
import com.gsim.root.RootBootstrapPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * NodeAgentChatService — 在当前 branch 节点下与 Agent 对话。
 */
public class NodeAgentChatService {
    private static final Logger log = LoggerFactory.getLogger(NodeAgentChatService.class);

    private final DataManager dm;
    private BranchContextRenderer renderer;
    private final OrchestratorAgent orchestrator;
    private final BranchMessageStore store;
    private ContextSessionManager ctxSessionManager;
    private Path dataRoot;
    private ApplicationContext appCtx;

    public NodeAgentChatService(DataManager dm, BranchContextRenderer renderer,
                                 OrchestratorAgent orchestrator,
                                 ContextSessionManager ctxSessionManager,
                                 Path dataRoot,
                                 ApplicationContext appCtx) {
        this.dm = dm;
        this.renderer = renderer;
        this.orchestrator = orchestrator;
        this.store = new BranchMessageStore(dm, dm.getDataRoot());
        this.ctxSessionManager = ctxSessionManager;
        this.dataRoot = dataRoot;
        this.appCtx = appCtx;
    }

    /** root 切换后更新 context 子系统。 */
    public void onRootChanged(BranchContextRenderer newRenderer,
                              ContextSessionManager newCtxSessionMgr,
                              Path newDataRoot,
                              ApplicationContext newAppCtx) {
        this.renderer = newRenderer;
        this.ctxSessionManager = newCtxSessionMgr;
        this.dataRoot = newDataRoot;
        this.appCtx = newAppCtx;
    }

    /**
     * 对话入口。
     * 如果 data 严格为空，自动 bootstrap 创建第一个 root。
     */
    public String chat(String userText) throws IOException {
        // 空 data bootstrap
        if (dm.needsRootBootstrap()) {
            return bootstrapFirstRoot(userText);
        }
        if (ctxSessionManager == null) {
            return "系统尚未初始化 root。请使用 /root create <rootId> <初始设定>。";
        }
        return doChat(userText);
    }

    /** 从空 data bootstrap 创建第一个 root。需要明确的初始化前缀。 */
    private String bootstrapFirstRoot(String userText) throws IOException {
        if (!RootBootstrapPolicy.isStrictlyEmptyDataRoot(dm.getDataRoot())) {
            return "已有 root 数据。创建或切换根节点需要用户显式命令。\n请使用：\n  /root create <rootId> <初始设定>\n  /root switch <rootId>\n  /root list";
        }

        // 检查 bootstrap 意图前缀
        var intent = com.gsim.root.BootstrapIntentParser.parse(userText);
        if (!intent.isBootstrap()) {
            return com.gsim.root.BootstrapIntentParser.nonBootstrapHint();
        }

        String worldContent = intent.worldContent();
        String title = com.gsim.root.RootIdGenerator.extractTitle(worldContent);
        String rootId = com.gsim.root.RootIdGenerator.generateFromContent(worldContent);
        String worldMd = com.gsim.root.RootIdGenerator.buildWorldMarkdown(title, worldContent);

        // Bootstrap
        dm.bootstrapFromEmpty(rootId, worldMd);

        // 重建 ContextSession + Knowledge
        appCtx.resolveKnowledgeForActiveRoot();
        var newRenderer = createRenderer();
        this.renderer = newRenderer;
        this.ctxSessionManager = createSessionManager(newRenderer);
        appCtx.setBranchContextRenderer(newRenderer);
        appCtx.setContextSessionManager(this.ctxSessionManager);

        // 确认消息
        StringBuilder sb = new StringBuilder();
        sb.append("已自动创建第一个根节点。\n");
        sb.append("Root ID: ").append(rootId).append("\n");
        sb.append("Title: ").append(title).append("\n");
        sb.append("Active Branch: branch.b0000-start\n\n");
        sb.append("世界观已写入为结构化初始设定。可以开始推演或对话。\n");
        sb.append("使用 /root status 查看状态。");
        return sb.toString();
    }

    private String doChat(String userText) throws IOException {
        String branchId = dm.getActiveBranch();
        String apiSessionId = "default";

        // 1. 获取或创建 ContextSession
        ContextSession cs = ctxSessionManager.getOrCreateActiveSession(apiSessionId, branchId);
        String csId = cs.sessionId();

        // 2. 写入 chat_user 到 BranchMessageStore
        String userMsgId = store.nextMessageId(branchId);
        BranchMessage userMsg = BranchMessage.create(userMsgId, "user", "chat_user", userText);
        store.appendMessage(branchId, userMsg);

        // 3. 写入 chat_user 到 SessionMessageStore
        SessionMessage smUser = new SessionMessage(
                "msg-" + System.nanoTime(), csId, branchId,
                "user", "chat_user", userText, Instant.now(),
                Map.of("branchId", branchId, "nodeId", branchId, "baseContextId", cs.baseContextId(), "source", "chat")
        );
        ctxSessionManager.appendMessage(csId, smUser);

        // 4. 渲染 LLM 输入
        String llmInput = ctxSessionManager.renderForLlm(apiSessionId, userText);

        // 5. 调用 LLM
        OrchestratorAgent.ChatResult result;
        List<SessionMessage> sessionMsgs = ctxSessionManager.getSessionMessages(csId);
        String baseMarkdown = ctxSessionManager.getBaseContextMarkdown(apiSessionId);
        if (baseMarkdown == null) baseMarkdown = "";

        List<SessionMessage> historyMsgs = sessionMsgs.stream()
                .filter(m -> !m.id().equals(smUser.id()))
                .toList();

        result = orchestrator.chatWithContextSession(baseMarkdown, historyMsgs, userText);

        if (!result.success()) {
            String errMsgId = store.nextMessageId(branchId);
            store.appendMessage(branchId, BranchMessage.create(errMsgId, "system", "error",
                    "LLM error: " + result.errorMessage()));
            SessionMessage smErr = SessionMessage.systemNote(csId, branchId,
                    "LLM error: " + result.errorMessage());
            ctxSessionManager.appendMessage(csId, smErr);
            return "[错误] " + result.errorMessage();
        }

        // 6. 写入 tool calls + tool results
        for (OrchestratorAgent.ToolCallRecord tc : result.toolCalls()) {
            String tcId = store.nextMessageId(branchId);
            store.appendMessage(branchId, BranchMessage.tool(tcId, "tool_call", tc.tool(),
                    tc.args().toString()));
            String trId = store.nextMessageId(branchId);
            store.appendMessage(branchId, BranchMessage.tool(trId, "tool_result", tc.tool(),
                    tc.result().success() ? tc.result().items().size() + " results" : tc.result().error()));

            SessionMessage smTc = SessionMessage.toolCall(csId, branchId, tc.tool(),
                    tc.args().toString());
            ctxSessionManager.appendMessage(csId, smTc);
            String toolResultContent = tc.result().success()
                    ? tc.result().items().size() + " results"
                    : "error: " + tc.result().error();
            ctxSessionManager.appendMessage(csId, SessionMessage.toolResult(csId, branchId, tc.tool(),
                    toolResultContent));
        }

        // 7. 写入 assistant 回复
        String asstId = store.nextMessageId(branchId);
        store.appendMessage(branchId, BranchMessage.create(asstId, "assistant", "chat_response", result.finalText()));
        ctxSessionManager.appendMessage(csId, new SessionMessage(
                "msg-" + System.nanoTime(), csId, branchId,
                "assistant", "chat_response", result.finalText(), Instant.now(),
                Map.of("branchId", branchId, "nodeId", branchId, "baseContextId", cs.baseContextId(), "source", "chat")
        ));

        return result.finalText();
    }

    private BranchContextRenderer createRenderer() {
        var messageStore = new BranchMessageStore(dm, dm.getDataRoot());
        var branchAnalyzer = new com.gsim.branch.BranchAnalyzer(dm, messageStore,
                new com.gsim.player.PlayerProfileManager(dm));
        return new BranchContextRenderer(dm, dm.getDataRoot(), messageStore, branchAnalyzer);
    }

    private ContextSessionManager createSessionManager(BranchContextRenderer renderer) {
        Path worldDir = dm.getDataRoot().resolve("worlds").resolve(dm.getActiveRootId());
        var sessionStore = new com.gsim.context.session.ContextSessionStore(worldDir);
        return new com.gsim.context.session.ContextSessionManager(sessionStore, renderer, dm, worldDir);
    }

    /** 列出当前 branch 的消息。 */
    public List<BranchMessage> listMessages() throws IOException {
        return store.listMessages(dm.getActiveBranch());
    }

    public List<BranchMessage> listMessages(String branchId) throws IOException {
        return store.listMessages(branchId);
    }

}
