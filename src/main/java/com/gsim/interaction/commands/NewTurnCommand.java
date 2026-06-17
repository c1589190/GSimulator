package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.campaign.Turn;

/**
 * /newturn — 结算当前回合并创建新回合。
 */
public class NewTurnCommand implements InteractionCommand {

    @Override
    public String name() {
        return "newturn";
    }

    @Override
    public String description() {
        return "结算当前回合并创建新回合";
    }

    @Override
    public String usage() {
        return "/newturn";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        var ctx = session.getContext();
        var campaignService = session.getCampaignService();
        var turnService = session.getTurnService();
        var actionService = session.getPlayerActionService();

        String campaignId = ctx.getCurrentCampaignId();
        if (campaignId == null) {
            return InteractionResult.fail("没有当前 campaign，无法创建新回合");
        }

        // 结算当前回合
        var currentTurn = turnService.getCurrentTurn();
        String oldTurnId = currentTurn.map(Turn::turnId).orElse("无");
        int oldActionCount = actionService.getActionCount();

        turnService.resolveCurrent();

        // 创建新回合
        Turn newTurn = turnService.createNext(campaignId);
        ctx.setCurrentTurnId(newTurn.turnId());
        campaignService.setCurrentTurnId(newTurn.turnId());
        campaignService.addTurnId(newTurn.turnId());

        // 清空行动列表
        actionService.clearActions();

        var sb = new StringBuilder();
        sb.append("========== 新回合已创建 ==========\n\n");
        sb.append("旧回合:     ").append(oldTurnId);
        if (oldActionCount > 0) {
            sb.append(" (").append(oldActionCount).append(" 条行动已结算)");
        }
        sb.append("\n");
        sb.append("新回合:     ").append(newTurn.turnId()).append("\n");
        sb.append("回合序号:   ").append(newTurn.index()).append("\n");
        sb.append("状态:       ").append(newTurn.status()).append("\n");
        sb.append("\n==================================\n");

        return InteractionResult.ok(sb.toString());
    }
}
