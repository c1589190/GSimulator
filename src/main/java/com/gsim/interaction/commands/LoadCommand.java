package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.campaign.Campaign;

import java.util.Optional;

/**
 * /load — 加载指定 campaign。
 */
public class LoadCommand implements InteractionCommand {

    @Override
    public String name() {
        return "load";
    }

    @Override
    public String description() {
        return "加载指定 campaign";
    }

    @Override
    public String usage() {
        return "/load <campaignId>";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args.length < 1 || args[0].isBlank()) {
            return InteractionResult.fail("用法: /load <campaignId>");
        }

        String campaignId = args[0];
        var ctx = session.getContext();
        var campaignService = session.getCampaignService();
        var turnService = session.getTurnService();
        var actionService = session.getPlayerActionService();

        Optional<Campaign> loaded = campaignService.load(campaignId);
        if (loaded.isEmpty()) {
            return InteractionResult.fail("Campaign 不存在: " + campaignId);
        }

        Campaign campaign = loaded.get();
        ctx.setCurrentCampaignId(campaignId);

        // 加载当前回合
        String currentTurnId = campaign.currentTurnId();
        if (currentTurnId != null) {
            turnService.clearCurrent();
            turnService.load(campaignId, currentTurnId);
            ctx.setCurrentTurnId(currentTurnId);
            actionService.loadActions(campaignId, currentTurnId);
        } else {
            ctx.setCurrentTurnId(null);
            actionService.clearActions();
        }

        var sb = new StringBuilder();
        sb.append("========== Campaign 已加载 ==========\n\n");
        sb.append("Campaign ID: ").append(campaign.campaignId()).append("\n");
        sb.append("名称:        ").append(campaign.name()).append("\n");
        sb.append("创建时间:    ").append(campaign.createdAt()).append("\n");
        sb.append("当前回合:    ").append(currentTurnId != null ? currentTurnId : "无").append("\n");
        sb.append("历史回合数:  ").append(campaign.turnIds().size()).append("\n");
        sb.append("\n=====================================\n");

        return InteractionResult.ok(sb.toString());
    }
}
