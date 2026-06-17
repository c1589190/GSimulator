package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.campaign.PlayerAction;

import java.util.List;

/**
 * /actions — 显示当前回合所有玩家行动。
 */
public class ActionsCommand implements InteractionCommand {

    @Override
    public String name() {
        return "actions";
    }

    @Override
    public String description() {
        return "显示当前回合所有玩家行动";
    }

    @Override
    public String usage() {
        return "/actions";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        List<PlayerAction> actions = session.getPlayerActionService().getActions();

        if (actions.isEmpty()) {
            return InteractionResult.ok("当前回合没有玩家行动。");
        }

        var sb = new StringBuilder();
        sb.append("===== 当前回合玩家行动 (").append(actions.size()).append(" 条) =====\n\n");

        for (int i = 0; i < actions.size(); i++) {
            PlayerAction a = actions.get(i);
            sb.append("[").append(i + 1).append("] ");
            sb.append("玩家: ").append(a.playerName()).append("\n");
            sb.append("    内容: ").append(a.content()).append("\n");
            sb.append("    时间: ").append(a.createdAt()).append("\n");
            sb.append("    标签: ").append(a.tags().isEmpty() ? "无" : String.join(", ", a.tags())).append("\n\n");
        }

        sb.append("============================\n");

        return InteractionResult.ok(sb.toString());
    }
}
