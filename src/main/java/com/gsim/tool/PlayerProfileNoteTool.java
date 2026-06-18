package com.gsim.tool;

import com.gsim.player.PlayerProfileManager;
import com.gsim.player.PlayerProfileUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * player_profile_note — 给玩家档案追加备注。
 */
public class PlayerProfileNoteTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerProfileNoteTool.class);
    public static final String NAME = "player_profile_note";

    private final PlayerProfileManager pm;

    public PlayerProfileNoteTool(PlayerProfileManager pm) {
        this.pm = pm;
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return "Append a note to a player's profile in players.md. "
                + "Use this when the user wants to add notes, observations, or supplementary info "
                + "to a player's profile. "
                + "Does NOT modify input.md, create branches, or advance time. "
                + "Params: playerName (required), note (required).";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String playerName = call.param("playerName", "");
        String note = call.param("note", "");

        if (playerName.isBlank()) return ToolResult.fail(NAME, "playerName is required");
        if (note.isBlank()) return ToolResult.fail(NAME, "note is required");

        PlayerProfileUpdate update = pm.appendPlayerNote(playerName, note);

        StringBuilder sb = new StringBuilder();
        sb.append(update.created() ? "已创建" : "已更新").append("玩家备注:\n");
        sb.append("玩家: ").append(update.playerName()).append("\n");
        sb.append("备注: ").append(update.content()).append("\n");
        sb.append("文件: ").append(pm.getPlayersPath()).append("\n");

        return ToolResult.ok(NAME, List.of(
                new ToolResult.Item(
                        "players.md 追加备注",
                        pm.getPlayersPath().toString(),
                        sb.toString(),
                        1.0)));
    }
}
