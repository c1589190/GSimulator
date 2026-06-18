package com.gsim.tool;

import com.gsim.player.PlayerProfileManager;
import com.gsim.player.PlayerProfileUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * player_profile_update — 创建或更新玩家档案字段。
 * 写入 players.md，不修改 input.md，不创建 branch，不推进时间。
 */
public class PlayerProfileUpdateTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerProfileUpdateTool.class);
    public static final String NAME = "player_profile_update";

    private final PlayerProfileManager pm;

    public PlayerProfileUpdateTool(PlayerProfileManager pm) {
        this.pm = pm;
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return "Create or update a player profile field in players.md. "
                + "Use this when the user says things like 'set player X's faction to Y', "
                + "'player X is a ...', 'add player X with identity ...', "
                + "or wants to update player profile settings. "
                + "Does NOT modify input.md, create branches, advance time, or call LLM. "
                + "Params: playerName (required), field (required), content (required). "
                + "Valid fields: type, faction, identity, resources, publicGoal, hiddenTendency, currentStatus, relationships, notes.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String playerName = call.param("playerName", "");
        String field = call.param("field", "");
        String content = call.param("content", "");

        if (playerName.isBlank()) return ToolResult.fail(NAME, "playerName is required");
        if (field.isBlank()) return ToolResult.fail(NAME, "field is required (type/faction/identity/resources/publicGoal/hiddenTendency/currentStatus/relationships/notes)");
        if (content.isBlank()) return ToolResult.fail(NAME, "content is required");

        PlayerProfileUpdate update = pm.updatePlayerField(playerName, field, content);

        StringBuilder sb = new StringBuilder();
        sb.append(update.created() ? "已创建" : "已更新").append("玩家档案:\n");
        sb.append("玩家: ").append(update.playerName()).append("\n");
        sb.append("字段: ").append(update.field()).append("\n");
        sb.append("内容: ").append(update.content()).append("\n");
        sb.append("文件: ").append(pm.getPlayersPath()).append("\n");
        sb.append("World: ").append(pm.getPlayersPath().getParent().getFileName()).append("\n");

        return ToolResult.ok(NAME, List.of(
                new ToolResult.Item(
                        "players.md " + (update.created() ? "创建" : "更新"),
                        pm.getPlayersPath().toString(),
                        sb.toString(),
                        1.0)));
    }
}
