package com.gsim.tool;

import com.gsim.data.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * PlayerInputTool — 将玩家行动或推演备注写入当前 world 的 input.md。
 *
 * 等价于 /player 命令，但供 Agent 在对话模式和推演模式中通过 ToolLoop 调用。
 * 不创建 branch，不清空 input.md，不调用 LLM。
 */
public class PlayerInputTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerInputTool.class);

    public static final String NAME = "player_input";

    private final DataManager dm;

    public PlayerInputTool(DataManager dm) {
        this.dm = dm;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Write a player action or simulation note to the current world's input.md. "
                + "Use this when the user says things like '玩家X要做某事', "
                + "'帮我记入当前轮输入', or describes a player action that should be recorded. "
                + "Does NOT create branches, clear input, or call LLM. "
                + "Params: playerName (required), content (required).";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String playerName = call.param("playerName", "");
        String content = call.param("content", "");

        if (playerName.isBlank()) {
            return ToolResult.fail(NAME, "playerName is required");
        }
        if (content.isBlank()) {
            return ToolResult.fail(NAME, "content is required");
        }

        try {
            dm.appendPlayerInput(playerName, content);
            log.info("PlayerInputTool: wrote input for player '{}'", playerName);

            String writtenLine = "* " + playerName + "：" + content;
            String summary = String.format(
                    "已写入 input.md：%s\nactiveWorld: %s\nactiveBranch: %s\ninputPath: data/worlds/%s/input.md",
                    writtenLine, dm.getActiveWorld(), dm.getActiveBranch(), dm.getActiveWorld());

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("input.md 已更新", "data/worlds/" + dm.getActiveWorld() + "/input.md",
                            summary, 1.0)));
        } catch (IOException e) {
            log.error("PlayerInputTool: failed to write input: {}", e.getMessage());
            return ToolResult.fail(NAME, "Failed to write input.md: " + e.getMessage());
        }
    }
}
