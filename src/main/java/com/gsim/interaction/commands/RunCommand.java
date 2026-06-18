package com.gsim.interaction.commands;

import com.gsim.chat.NodeAgentChatService;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /run — 已废弃。
 *
 * <p>不再执行旧的回合行动结算逻辑。
 * 所有内容转发到统一 Agent 入口（NodeAgentChatService）。
 */
public class RunCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(RunCommand.class);

    private final NodeAgentChatService chatService;

    public RunCommand(NodeAgentChatService chatService) {
        this.chatService = chatService;
    }

    @Override public String name() { return "run"; }

    @Override public String description() {
        return "[已废弃] 请直接用自然语言描述本回合要推演什么";
    }

    @Override public String usage() {
        return "/run <内容> — 已废弃，请直接输入自然语言。例如：请根据当前玩家行动结算本回合，重点考虑补给和军警反应。";
    }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        String full = String.join(" ", args).trim();

        if (full.isEmpty()) {
            return InteractionResult.fail(
                    "/run 已废弃。请直接用自然语言描述本回合要推演什么。\n"
                            + "例如：请根据当前玩家行动结算本回合，重点考虑补给、军警反应和感染者态度。");
        }

        // 转发到统一 Agent 入口，附加提示
        String enriched = full + "\n\n[系统提示: 用户使用了已废弃的 /run。"
                + "请把后续内容视为自然语言本回合推演/结算请求。"
                + "如果需要读取玩家行动，请使用 player_input 或现有行动列表。]";

        try {
            String reply = chatService.chat(enriched);
            return InteractionResult.ok("run → chat", reply);
        } catch (Exception e) {
            log.error("/run forward failed: {}", e.getMessage(), e);
            return InteractionResult.fail("Agent forward failed: " + e.getMessage());
        }
    }
}
