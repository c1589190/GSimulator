package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.campaign.PlayerAction;

/**
 * /player — 登记玩家行动。
 */
public class PlayerCommand implements InteractionCommand {

    @Override
    public String name() {
        return "player";
    }

    @Override
    public String description() {
        return "登记玩家行动";
    }

    @Override
    public String usage() {
        return "/player <玩家名> <行动内容>";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args.length < 2) {
            return InteractionResult.fail("用法: /player <玩家名> <行动内容>");
        }

        String playerName = args[0];
        String content = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));

        if (playerName.isBlank()) {
            return InteractionResult.fail("玩家名不能为空");
        }
        if (content.isBlank()) {
            return InteractionResult.fail("行动内容不能为空");
        }

        var ctx = session.getContext();
        String campaignId = ctx.getCurrentCampaignId();
        String turnId = ctx.getCurrentTurnId();

        if (campaignId == null) {
            return InteractionResult.fail("没有当前 campaign，无法登记行动");
        }
        if (turnId == null) {
            return InteractionResult.fail("没有当前 turn，无法登记行动");
        }

        PlayerAction action = session.getPlayerActionService()
                .addAction(campaignId, turnId, playerName, content);

        return InteractionResult.ok(
                "已记录玩家行动：" + action.playerName() + " / " + action.turnId(),
                "已记录玩家行动：" + action.playerName() + " (id=" + action.id() + ")"
        );
    }
}
