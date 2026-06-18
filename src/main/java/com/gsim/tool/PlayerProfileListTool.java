package com.gsim.tool;

import com.gsim.player.PlayerProfile;
import com.gsim.player.PlayerProfileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * player_profile_list — 列出当前 world 的玩家档案。
 */
public class PlayerProfileListTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerProfileListTool.class);
    public static final String NAME = "player_profile_list";

    private final PlayerProfileManager pm;

    public PlayerProfileListTool(PlayerProfileManager pm) {
        this.pm = pm;
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return "List all player profiles in the current world's players.md. "
                + "Returns each player's name, faction, identity, and current status. "
                + "Use this when the user asks 'who are the players', "
                + "'list players', or wants to see available player profiles. "
                + "No parameters required.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        List<PlayerProfile> profiles = pm.listPlayers();

        if (profiles.isEmpty()) {
            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item(
                            "无玩家档案",
                            pm.getPlayersPath().toString(),
                            "当前 world 中无玩家档案。使用 /players add <玩家名> 或 player_profile_update 创建。",
                            0.0)));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("玩家档案列表 (").append(profiles.size()).append(" 人):\n");
        for (PlayerProfile p : profiles) {
            sb.append("- ").append(p.name())
                    .append(" | 阵营: ").append(p.faction())
                    .append(" | 身份: ").append(p.identity())
                    .append(" | 状态: ").append(p.currentStatus())
                    .append("\n");
        }

        return ToolResult.ok(NAME, List.of(
                new ToolResult.Item(
                        "玩家档案列表",
                        pm.getPlayersPath().toString(),
                        sb.toString(),
                        1.0)));
    }
}
