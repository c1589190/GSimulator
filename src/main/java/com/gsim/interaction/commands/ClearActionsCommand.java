package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;

/**
 * /clearactions — 清空当前回合未结算行动。
 */
public class ClearActionsCommand implements InteractionCommand {

    @Override
    public String name() {
        return "clearactions";
    }

    @Override
    public String description() {
        return "清空当前回合未结算行动";
    }

    @Override
    public String usage() {
        return "/clearactions";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        int count = session.getPlayerActionService().getActionCount();

        if (count == 0) {
            return InteractionResult.ok("当前回合没有行动需要清空。");
        }

        session.getPlayerActionService().clearActions();

        return InteractionResult.ok(
                "清空了 " + count + " 条未结算行动。",
                "已清空 " + count + " 条未结算行动。"
        );
    }
}
