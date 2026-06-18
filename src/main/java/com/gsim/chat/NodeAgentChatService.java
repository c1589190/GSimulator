package com.gsim.chat;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.context.BranchContextRenderer;
import com.gsim.data.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * NodeAgentChatService — 在当前 branch 节点下与 Agent 对话。
 * 对话记录以 message block 格式写入 branch 文件。
 */
public class NodeAgentChatService {
    private static final Logger log = LoggerFactory.getLogger(NodeAgentChatService.class);

    private final DataManager dm;
    private final BranchContextRenderer renderer;
    private final OrchestratorAgent orchestrator;
    private final BranchMessageStore store;

    public NodeAgentChatService(DataManager dm, BranchContextRenderer renderer,
                                 OrchestratorAgent orchestrator) {
        this.dm = dm;
        this.renderer = renderer;
        this.orchestrator = orchestrator;
        this.store = new BranchMessageStore(dm, dm.getDataRoot());
    }

    /** 对话：写入 user 消息，调用 LLM，写入 assistant/tool 回复。 */
    public String chat(String userText) throws IOException {
        String branchId = dm.getActiveBranch();

        // 1. 写入 user 消息
        String userMsgId = store.nextMessageId(branchId);
        BranchMessage userMsg = BranchMessage.create(userMsgId, "user", "chat_user", userText);
        store.appendMessage(branchId, userMsg);

        // 2. 渲染上下文
        String contextMd = renderer.renderAsMarkdown();

        // 3. 调用 LLM
        OrchestratorAgent.ChatResult result = orchestrator.chatWithRenderedContext(contextMd, userText);

        if (!result.success()) {
            String errMsgId = store.nextMessageId(branchId);
            BranchMessage errMsg = BranchMessage.create(errMsgId, "system", "error",
                    "LLM error: " + result.errorMessage());
            store.appendMessage(branchId, errMsg);
            return "[错误] " + result.errorMessage();
        }

        // 4. 写入 tool_call / tool_result
        for (OrchestratorAgent.ToolCallRecord tc : result.toolCalls()) {
            String tcId = store.nextMessageId(branchId);
            store.appendMessage(branchId, BranchMessage.tool(tcId, "tool_call", tc.tool(),
                    tc.args().toString()));
            String trId = store.nextMessageId(branchId);
            store.appendMessage(branchId, BranchMessage.tool(trId, "tool_result", tc.tool(),
                    tc.result().success() ? tc.result().items().size() + " results" : tc.result().error()));
        }

        // 5. 写入 assistant 回复
        String asstId = store.nextMessageId(branchId);
        BranchMessage asstMsg = BranchMessage.create(asstId, "assistant", "chat_response", result.finalText());
        store.appendMessage(branchId, asstMsg);

        return result.finalText();
    }

    /** 列出当前 branch 的消息。 */
    public List<BranchMessage> listMessages() throws IOException {
        return store.listMessages(dm.getActiveBranch());
    }

    /** 列出指定 branch 的消息。 */
    public List<BranchMessage> listMessages(String branchId) throws IOException {
        return store.listMessages(branchId);
    }
}
