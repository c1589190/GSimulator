package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.campaign.Turn;

import java.util.Optional;

/**
 * /turn — 切换到指定回合。
 */
public class TurnCommand implements InteractionCommand {

    @Override
    public String name() {
        return "turn";
    }

    @Override
    public String description() {
        return "切换到指定回合";
    }

    @Override
    public String usage() {
        return "/turn <turnId>";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args.length < 1 || args[0].isBlank()) {
            return InteractionResult.fail("用法: /turn <turnId>");
        }

        String turnId = args[0];
        var ctx = session.getContext();
        String campaignId = ctx.getCurrentCampaignId();

        if (campaignId == null) {
            return InteractionResult.fail("没有当前 campaign，无法切换回合");
        }

        var turnService = session.getTurnService();
        var campaignService = session.getCampaignService();
        var actionService = session.getPlayerActionService();

        Optional<Turn> loaded = turnService.load(campaignId, turnId);
        if (loaded.isEmpty()) {
            return InteractionResult.fail("回合不存在: " + turnId + " (campaign: " + campaignId + ")");
        }

        Turn turn = loaded.get();
        ctx.setCurrentTurnId(turnId);
        campaignService.setCurrentTurnId(turnId);
        actionService.loadActions(campaignId, turnId);

        var sb = new StringBuilder();
        sb.append("========== 已切换到回合 ==========\n\n");
        sb.append("Turn ID:     ").append(turn.turnId()).append("\n");
        sb.append("回合序号:    ").append(turn.index()).append("\n");
        sb.append("状态:        ").append(turn.status()).append("\n");
        sb.append("创建时间:    ").append(turn.createdAt()).append("\n");
        if (turn.resolvedAt() != null) {
            sb.append("结算时间:    ").append(turn.resolvedAt()).append("\n");
        }
        sb.append("当前行动数:  ").append(actionService.getActionCount()).append("\n");
        sb.append("\n==================================\n");

        return InteractionResult.ok(sb.toString());
    }
}
