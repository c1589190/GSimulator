package com.gsim.interaction.commands;

import com.gsim.chat.BranchMessage;
import com.gsim.chat.NodeAgentChatService;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ChatCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(ChatCommand.class);
    private final NodeAgentChatService chatService;

    public ChatCommand(NodeAgentChatService chatService) { this.chatService = chatService; }

    @Override public String name() { return "chat"; }
    @Override public String description() {
        return "统一 Agent 入口 — 对话、推演、资料查询（/sim 和 /run 已废弃）";
    }
    @Override public String usage() { return "/chat <内容> 或直接输入自然语言"; }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        // 检查 LLM 是否可用
        if (session.getLlmManager() == null || !session.getLlmManager().isAvailable()) {
            return InteractionResult.fail(
                    "LLM is not configured.\n"
                            + "Run /config init to set up your LLM, or /config status to see current config.");
        }

        String full = String.join(" ", args).trim();
        if (full.isEmpty()) return InteractionResult.fail("Usage: /chat <内容>");
        try {
            String reply = chatService.chat(full);
            return InteractionResult.ok("chat done", reply);
        } catch (Exception e) {
            return InteractionResult.fail("Chat failed: " + e.getMessage());
        }
    }

    /** /messages 子命令处理（独立入口，通过 MessagesSubCommand 调用）。 */
    public InteractionResult handleMessages(String[] args) {
        String full = String.join(" ", args).trim();
        String[] t = full.split("\\s+");
        try {
            String bid = t.length > 1 && "show".equals(t[0]) ? t[1] : null;
            List<BranchMessage> msgs = bid != null ? chatService.listMessages(bid) : chatService.listMessages();
            StringBuilder sb = new StringBuilder();
            sb.append("Messages").append(bid != null ? " for " + bid : "").append(": ").append(msgs.size()).append("\n\n");
            for (BranchMessage m : msgs) {
                sb.append("[").append(m.id()).append("] ").append(m.role()).append("/").append(m.type());
                if (m.toolName() != null) sb.append(" tool=").append(m.toolName());
                sb.append("\n    ").append(m.content().length() > 200 ? m.content().substring(0, 200) + "..." : m.content()).append("\n\n");
            }
            if (msgs.isEmpty()) sb.append("(no messages)\n");
            return InteractionResult.ok(sb.toString().trim());
        } catch (Exception e) {
            return InteractionResult.fail(e.getMessage());
        }
    }
}
