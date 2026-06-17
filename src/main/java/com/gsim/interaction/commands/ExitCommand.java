package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;

/**
 * /exit — 保存状态并退出程序。
 */
public class ExitCommand implements InteractionCommand {

    private final Runnable onExit;

    public ExitCommand(Runnable onExit) {
        this.onExit = onExit;
    }

    @Override
    public String name() {
        return "exit";
    }

    @Override
    public String description() {
        return "保存当前状态并退出";
    }

    @Override
    public String usage() {
        return "/exit";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        // 保存状态
        session.getCampaignService().save();
        session.getTurnService().save();

        // 触发退出回调
        if (onExit != null) {
            onExit.run();
        }

        return InteractionResult.ok("再见！", "再见！");
    }
}
