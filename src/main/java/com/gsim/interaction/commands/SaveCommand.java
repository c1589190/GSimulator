package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;

/**
 * /save — 手动保存当前状态。
 */
public class SaveCommand implements InteractionCommand {

    @Override
    public String name() {
        return "save";
    }

    @Override
    public String description() {
        return "手动保存当前状态";
    }

    @Override
    public String usage() {
        return "/save";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        session.getCampaignService().save();
        session.getTurnService().save();

        return InteractionResult.ok(
                "状态已保存。",
                "状态已保存。"
        );
    }
}
