package com.gsim.tool;

import com.gsim.player.PlayerProfile;
import com.gsim.player.PlayerProfileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * player_profile_get — 读取指定玩家档案。
 */
public class PlayerProfileGetTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerProfileGetTool.class);
    public static final String NAME = "player_profile_get";

    private final PlayerProfileManager pm;

    public PlayerProfileGetTool(PlayerProfileManager pm) {
        this.pm = pm;
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return "Read a specific player's profile from players.md. "
                + "Returns the complete profile including faction, identity, resources, goals, tendencies, status, relationships, and notes. "
                + "Use this when the user asks about a specific player's settings or profile. "
                + "Params: playerName (required).";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String playerName = call.param("playerName", "");
        if (playerName.isBlank()) {
            return ToolResult.fail(NAME, "playerName is required");
        }

        Optional<PlayerProfile> opt = pm.getPlayer(playerName);
        if (opt.isEmpty()) {
            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item(
                            "未找到: " + playerName,
                            pm.getPlayersPath().toString(),
                            "玩家 '" + playerName + "' 在 players.md 中不存在。\n文件路径: " + pm.getPlayersPath(),
                            0.0)));
        }

        PlayerProfile p = opt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("玩家: ").append(p.name()).append("\n");
        sb.append("  类型: ").append(p.type()).append("\n");
        sb.append("  阵营: ").append(p.faction()).append("\n");
        sb.append("  身份: ").append(p.identity()).append("\n");
        sb.append("  控制资源: ").append(p.resources()).append("\n");
        sb.append("  公开目标: ").append(p.publicGoal()).append("\n");
        sb.append("  隐藏倾向: ").append(p.hiddenTendency()).append("\n");
        sb.append("  当前状态: ").append(p.currentStatus()).append("\n");
        sb.append("  关系: ").append(p.relationships()).append("\n");
        sb.append("  备注: ").append(p.notes()).append("\n");

        return ToolResult.ok(NAME, List.of(
                new ToolResult.Item(
                        p.name() + " 玩家档案",
                        pm.getPlayersPath().toString(),
                        sb.toString(),
                        1.0)));
    }
}
