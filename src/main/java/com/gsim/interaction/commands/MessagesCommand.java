package com.gsim.interaction.commands;

import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * MessagesCommand — 查看 branch 消息块历史。
 *
 * /messages              显示当前 active branch 消息列表
 * /messages show <id>    显示指定 branch 消息列表
 */
public class MessagesCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(MessagesCommand.class);
    private final BranchMessageStore store;
    private final DataManager dm;

    public MessagesCommand(BranchMessageStore store, DataManager dm) {
        this.store = store;
        this.dm = dm;
    }

    @Override public String name() { return "messages"; }
    @Override public String description() { return "查看当前或指定 branch 的消息历史"; }
    @Override public String usage() { return "/messages [show <branchId>]"; }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        String full = String.join(" ", args).trim();
        String[] t = full.split("\\s+");

        try {
            String branchId;
            if (t.length >= 2 && "show".equals(t[0])) {
                branchId = DataManager.normalizeBranchId(t[1]);
            } else if (t.length == 1 && !t[0].isBlank()) {
                // "/messages show <id>" with space but t[0] is "show"
                if ("show".equals(t[0]) && t.length >= 2) {
                    branchId = DataManager.normalizeBranchId(t[1]);
                } else {
                    branchId = dm.getActiveBranch();
                }
            } else {
                branchId = dm.getActiveBranch();
            }

            return renderMessages(branchId);
        } catch (Exception e) {
            log.error("/messages failed: {}", e.getMessage(), e);
            return InteractionResult.fail(e.getMessage());
        }
    }

    /** 实际处理逻辑，供 ConsoleInteractionAdapter 直接调用。 */
    public InteractionResult handleRaw(String rawArgs) {
        String trimmed = rawArgs != null ? rawArgs.trim() : "";
        String[] t = trimmed.split("\\s+");
        try {
            String branchId;
            if (t.length >= 1 && "show".equals(t[0]) && t.length >= 2) {
                branchId = DataManager.normalizeBranchId(t[1]);
            } else if (t.length >= 1 && !t[0].isBlank()) {
                branchId = DataManager.normalizeBranchId(t[0]);
            } else {
                branchId = dm.getActiveBranch();
            }
            return renderMessages(branchId);
        } catch (Exception e) {
            return InteractionResult.fail(e.getMessage());
        }
    }

    /** 渲染指定 branch 的消息列表。 */
    public InteractionResult renderMessages(String branchId) throws Exception {
        List<BranchMessage> msgs = store.listMessages(branchId);
        StringBuilder sb = new StringBuilder();
        sb.append("Messages for ").append(branchId).append(": ").append(msgs.size()).append("\n\n");

        if (msgs.isEmpty()) {
            sb.append("(no messages)\n");
        }

        for (BranchMessage m : msgs) {
            sb.append("[").append(m.id()).append("] ")
                    .append(m.role()).append("/").append(m.type());
            if (m.toolName() != null && !m.toolName().isBlank()) {
                sb.append(" tool=").append(m.toolName());
            }
            sb.append(" created=").append(m.createdAt()).append("\n");
            String preview = m.content().length() > 200
                    ? m.content().substring(0, 200).replace("\n", "\\n") + "..."
                    : m.content().replace("\n", "\\n");
            sb.append("    ").append(preview).append("\n\n");
        }
        return InteractionResult.ok(sb.toString().trim());
    }
}
