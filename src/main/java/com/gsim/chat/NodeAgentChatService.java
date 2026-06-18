package com.gsim.chat;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.context.BranchContextRenderer;
import com.gsim.context.session.ContextSession;
import com.gsim.context.session.ContextSessionManager;
import com.gsim.context.session.SessionMessage;
import com.gsim.data.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * NodeAgentChatService — 在当前 branch 节点下与 Agent 对话。
 *
 * <p>新路径（ContextSession 模式）：
 * <ol>
 *   <li>创建/复用 ContextSession，渲染一次 BaseContextSnapshot</li>
 *   <li>写入 chat_user 到 SessionMessageStore + BranchMessageStore</li>
 *   <li>renderForLlm() 取 base + session messages</li>
 *   <li>调用 OrchestratorAgent.chatWithContextSession()</li>
 *   <li>写入工具调用和回复到两个 store</li>
 * </ol>
 *
 * <p>旧路径（renderAsMarkdown）保留为 debug。
 */
public class NodeAgentChatService {
    private static final Logger log = LoggerFactory.getLogger(NodeAgentChatService.class);

    private final DataManager dm;
    private final BranchContextRenderer renderer;
    private final OrchestratorAgent orchestrator;
    private final BranchMessageStore store;
    private final ContextSessionManager ctxSessionManager;

    public NodeAgentChatService(DataManager dm, BranchContextRenderer renderer,
                                 OrchestratorAgent orchestrator,
                                 ContextSessionManager ctxSessionManager) {
        this.dm = dm;
        this.renderer = renderer;
        this.orchestrator = orchestrator;
        this.store = new BranchMessageStore(dm, dm.getDataRoot());
        this.ctxSessionManager = ctxSessionManager;
    }

    /**
     * 对话：写入 user 消息，调用 LLM，写入 assistant/tool 回复。
     * 使用 ContextSession 路径。
     */
    public String chat(String userText) throws IOException {
        String branchId = dm.getActiveBranch();
        String apiSessionId = "default";

        // 1. 获取或创建 ContextSession
        ContextSession cs = ctxSessionManager.getOrCreateActiveSession(apiSessionId, branchId);
        String csId = cs.sessionId();

        // 2. 写入 chat_user 到 BranchMessageStore（长期档案）
        String userMsgId = store.nextMessageId(branchId);
        BranchMessage userMsg = BranchMessage.create(userMsgId, "user", "chat_user", userText);
        store.appendMessage(branchId, userMsg);

        // 3. 写入 chat_user 到 SessionMessageStore（当前对话段）
        SessionMessage smUser = new SessionMessage(
                "msg-" + System.nanoTime(), csId, branchId,
                "user", "chat_user", userText, Instant.now(),
                Map.of("branchId", branchId, "nodeId", branchId, "baseContextId", cs.baseContextId(), "source", "chat")
        );
        ctxSessionManager.appendMessage(csId, smUser);

        // 4. 渲染 LLM 输入（BaseContext + session messages + user input）
        String llmInput = ctxSessionManager.renderForLlm(apiSessionId, userText);

        // 5. 调用 LLM（ContextSession 路径）
        OrchestratorAgent.ChatResult result;
        List<SessionMessage> sessionMsgs = ctxSessionManager.getSessionMessages(csId);
        // 去掉当前 user 消息（已作为参数传入）
        String baseMarkdown = ctxSessionManager.getBaseContextMarkdown(apiSessionId);
        if (baseMarkdown == null) baseMarkdown = "";

        // 过滤掉刚追加的 user 消息（LLM 输入会单独传 userText）
        List<SessionMessage> historyMsgs = sessionMsgs.stream()
                .filter(m -> !m.id().equals(smUser.id()))
                .toList();

        result = orchestrator.chatWithContextSession(baseMarkdown, historyMsgs, userText);

        if (!result.success()) {
            String errMsgId = store.nextMessageId(branchId);
            BranchMessage errMsg = BranchMessage.create(errMsgId, "system", "error",
                    "LLM error: " + result.errorMessage());
            store.appendMessage(branchId, errMsg);

            SessionMessage smErr = SessionMessage.systemNote(csId, branchId,
                    "LLM error: " + result.errorMessage());
            ctxSessionManager.appendMessage(csId, smErr);

            return "[错误] " + result.errorMessage();
        }

        // 6. 写入 tool_call / tool_result 到两个 store
        for (OrchestratorAgent.ToolCallRecord tc : result.toolCalls()) {
            // BranchMessageStore
            String tcId = store.nextMessageId(branchId);
            store.appendMessage(branchId, BranchMessage.tool(tcId, "tool_call", tc.tool(),
                    tc.args().toString()));
            String trId = store.nextMessageId(branchId);
            store.appendMessage(branchId, BranchMessage.tool(trId, "tool_result", tc.tool(),
                    tc.result().success() ? tc.result().items().size() + " results" : tc.result().error()));

            // SessionMessageStore
            SessionMessage smTc = SessionMessage.toolCall(csId, branchId, tc.tool(),
                    tc.args().toString());
            ctxSessionManager.appendMessage(csId, smTc);

            String toolResultContent = tc.result().success()
                    ? tc.result().items().size() + " results"
                    : "error: " + tc.result().error();
            SessionMessage smTr = SessionMessage.toolResult(csId, branchId, tc.tool(),
                    toolResultContent);
            ctxSessionManager.appendMessage(csId, smTr);
        }

        // 7. 写入 assistant 回复到两个 store
        String asstId = store.nextMessageId(branchId);
        BranchMessage asstMsg = BranchMessage.create(asstId, "assistant", "chat_response", result.finalText());
        store.appendMessage(branchId, asstMsg);

        SessionMessage smAsst = new SessionMessage(
                "msg-" + System.nanoTime(), csId, branchId,
                "assistant", "chat_response", result.finalText(), Instant.now(),
                Map.of("branchId", branchId, "nodeId", branchId, "baseContextId", cs.baseContextId(), "source", "chat")
        );
        ctxSessionManager.appendMessage(csId, smAsst);

        return result.finalText();
    }

    /** 列出当前 branch 的消息（从 BranchMessageStore）。 */
    public List<BranchMessage> listMessages() throws IOException {
        return store.listMessages(dm.getActiveBranch());
    }

    /** 列出指定 branch 的消息（从 BranchMessageStore）。 */
    public List<BranchMessage> listMessages(String branchId) throws IOException {
        return store.listMessages(branchId);
    }
}
