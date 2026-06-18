package com.gsim.interaction.commands;

import com.gsim.chat.NodeAgentChatService;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /sim — 已废弃。
 *
 * <p>不再执行旧的完整上下文推演逻辑。
 * 所有内容转发到统一 Agent 入口（NodeAgentChatService）。
 */
public class SimCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(SimCommand.class);

    private final NodeAgentChatService chatService;

    public SimCommand(NodeAgentChatService chatService) {
        this.chatService = chatService;
    }

    @Override public String name() { return "sim"; }

    @Override public String description() {
        return "[已废弃] 请直接用自然语言描述推演请求";
    }

    @Override public String usage() {
        return "/sim <内容> — 已废弃，请直接输入自然语言。例如：老威廉想扩大感染者救援点，按当前分支继续推演。";
    }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        String full = String.join(" ", args).trim();

        if (full.isEmpty()) {
            return InteractionResult.fail(
                    "/sim 已废弃。请直接用自然语言描述你想推演的内容。\n"
                            + "例如：老威廉想扩大感染者救援点，按当前分支继续推演。");
        }

        // 转发到统一 Agent 入口，附加提示
        String enriched = full + "\n\n[系统提示: 用户使用了已废弃的 /sim。请把后续内容视为自然语言推演请求。]";

        try {
            String reply = chatService.chat(enriched);
            return InteractionResult.ok("sim → chat", reply);
        } catch (Exception e) {
            log.error("/sim forward failed: {}", e.getMessage(), e);
            return InteractionResult.fail("Agent forward failed: " + e.getMessage());
        }
    }
}
