package com.gsim.interaction.commands;

import com.gsim.campaign.PlayerAction;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /player — 登记玩家行动，写入旧 PlayerActionService 和 data/input.md。
 */
public class PlayerCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(PlayerCommand.class);
    private final DataManager dm;

    public PlayerCommand(DataManager dm) { this.dm = dm; }

    @Override public String name() { return "player"; }
    @Override public String description() { return "登记玩家行动"; }
    @Override public String usage() { return "/player <玩家名> <行动内容>"; }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args.length < 2) return InteractionResult.fail("用法: /player <玩家名> <行动内容>");

        String playerName = args[0];
        String content = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        if (playerName.isBlank()) return InteractionResult.fail("玩家名不能为空");
        if (content.isBlank()) return InteractionResult.fail("行动内容不能为空");

        // 旧 PlayerActionService
        var ctx = session.getContext();
        String campaignId = ctx.getCurrentCampaignId();
        String turnId = ctx.getCurrentTurnId();
        if (campaignId == null || turnId == null) return InteractionResult.fail("campaign/turn 未初始化");

        PlayerAction action = session.getPlayerActionService()
                .addAction(campaignId, turnId, playerName, content);

        // 新：写入 data/input.md
        try {
            dm.appendPlayerInput(playerName, content);
        } catch (Exception e) {
            log.warn("Failed to write input.md: {}", e.getMessage());
        }

        return InteractionResult.ok(
                "已记录玩家行动：" + action.playerName() + " / " + action.turnId(),
                "已记录玩家行动：" + action.playerName() + " (id=" + action.id() + ")\n" +
                "World: " + dm.getActiveWorld() + "\n" +
                "Branch: " + dm.getActiveBranch() + "\n" +
                "已写入: data/worlds/" + dm.getActiveWorld() + "/input.md");
    }
}
