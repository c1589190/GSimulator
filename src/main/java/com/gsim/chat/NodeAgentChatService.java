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
        // 清洗 ANSI 控制字符
        String cleaned = com.gsim.root.TextSanitizer.safeStrip(userText).trim();
        if (cleaned.isEmpty()) {
            return ""; // 控制字符空输入，忽略
        }
        // 空 data bootstrap
        if (dm.needsRootBootstrap()) {
            return bootstrapFirstRoot(cleaned);
        }
        if (ctxSessionManager == null) {
            return "系统尚未初始化 root。请使用 /root create <rootId> <初始设定>。";
        }
        return doChat(cleaned);
    }

    /** 从空 data bootstrap 创建第一个 root。任意非空自然语言输入都允许。 */
    private String bootstrapFirstRoot(String userText) throws IOException {
        if (!RootBootstrapPolicy.isStrictlyEmptyDataRoot(dm.getDataRoot())) {
            return "已有 root 数据。创建或切换根节点需要用户显式命令。\n请使用：\n  /root create <rootId> <初始设定>\n  /root switch <rootId>\n  /root list";
        }

        // 解析 bootstrap 意图（data 为空时任意非空文本都允许）
        var intent = com.gsim.root.BootstrapIntentParser.parse(userText, true);
        if (!intent.shouldBootstrap()) {
            return ""; // 空输入，忽略
        }

        // 生成结构化 draft（默认走 deterministic fallback，仅当
        // bootstrap.root.llm.enabled=true 时调用 LLM）
        boolean llmEnabled = appCtx.getConfig().isBootstrapRootLlmEnabled();
        var generator = new com.gsim.root.BootstrapWorldDraftGenerator(
                appCtx.getLlmClient(), appCtx.getConfig().getLlmModel(), llmEnabled);
        var draft = generator.generate(intent);

        String rootId = draft.rootIdSuggestion();
        // 确保 rootId 有效
        if (!com.gsim.root.RootIdGenerator.isValidRootId(rootId)) {
            rootId = com.gsim.root.RootIdGenerator.suggestRootId(intent.sanitizedRequest());
        }

        // Bootstrap
        dm.bootstrapFromEmpty(rootId, draft);

        // 重建 ContextSession + Knowledge
        appCtx.resolveKnowledgeForActiveRoot();
        var newRenderer = createRenderer();
        this.renderer = newRenderer;
        this.ctxSessionManager = createSessionManager(newRenderer);
        appCtx.setBranchContextRenderer(newRenderer);
        appCtx.setContextSessionManager(this.ctxSessionManager);
        // 触发 root 就绪回调（重新注册 memory tools 等）
        appCtx.fireOnRootReady();

        // 构建确认消息
        StringBuilder sb = new StringBuilder();
        sb.append("快速创建基础根节点。建议继续对话以完善世界观设定。\n\n");
        sb.append("Root ID: ").append(rootId).append("\n");
        sb.append("Title: ").append(draft.title()).append("\n");
        sb.append("Active Branch: branch.b0000-start\n\n");
        sb.append("已生成基础设定文件：\n");
        sb.append("- world.md：基础设定\n");
        sb.append("- entities.md：主要势力/人物框架\n");
        sb.append("- rules.md：推演规则\n");
        sb.append("- players.md：玩家资料结构\n");

        if (!draft.warnings().isEmpty()) {
            sb.append("\n注意：");
            for (String w : draft.warnings()) {
                sb.append("\n- ").append(w);
            }
        }

        sb.append("\n\n使用 /root status 查看状态，直接输入文本与 Agent 对话。");
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

        // 5. 构建 AgentContextMeta
        String rootId = dm.getActiveRootId();
        String activeB = dm.getActiveBranch();
        java.util.List<String> branchPath = java.util.List.of(); // DataManager.getBranchChain not available directly
        java.util.List<String> parentBranches = java.util.List.of();
        try {
            var chain = dm.getBranchChain(activeB);
            branchPath = chain.stream()
                    .map(d -> d != null ? d.id() : "?")
                    .filter(id -> id != null && !id.startsWith("root."))
                    .collect(java.util.stream.Collectors.toList());
            if (branchPath.size() > 1) {
                parentBranches = branchPath.subList(0, branchPath.size() - 1);
            }
        } catch (Exception e) {
            // fallback empty
        }
        var contextMeta = new com.gsim.agent.AgentContextMeta(
                rootId, activeB, "FULL_CONTEXT", true,
                "current_context_builder_default",
                branchPath, parentBranches, true);

        // 6. 调用 LLM
        OrchestratorAgent.ChatResult result;
        List<SessionMessage> sessionMsgs = ctxSessionManager.getSessionMessages(csId);
        String baseMarkdown = ctxSessionManager.getBaseContextMarkdown(apiSessionId);
        if (baseMarkdown == null) baseMarkdown = "";

        List<SessionMessage> historyMsgs = sessionMsgs.stream()
                .filter(m -> !m.id().equals(smUser.id()))
                .toList();

        result = orchestrator.chatWithContextSession(baseMarkdown, historyMsgs, userText, contextMeta);

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
