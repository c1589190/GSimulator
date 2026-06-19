package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * player_action_append — 向当前 active branch 追加一条玩家行动记录。
 * 写入 branch 文件的 "### 玩家行动记录" 区，不影响 input.md。
 */
public class PlayerActionAppendTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerActionAppendTool.class);
    public static final String NAME = "player_action_append";

    private final DataManager dm;

    public PlayerActionAppendTool(DataManager dm) {
        this.dm = dm;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "向当前 active branch 追加一条玩家行动记录（写入 branch 文件的玩家行动记录区，不是 input.md）。"
                + "参数: branchId (可选，默认current), playerName (必填), content (必填), summary (可选)。"
                + "返回 actId, branchId, filePath。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!dm.hasActiveRoot()) {
            return ToolResult.fail(NAME, "NO_ACTIVE_ROOT");
        }

        String branchIdParam = call.param("branchId", "current");
        String branchId;
        if ("current".equals(branchIdParam)) {
            branchId = dm.getActiveBranchId();
            if (branchId == null) return ToolResult.fail(NAME, "NO_ACTIVE_BRANCH");
        } else {
            branchId = DataManager.normalizeBranchId(branchIdParam);
        }

        String playerName = call.param("playerName", "");
        if (playerName.isBlank()) return ToolResult.fail(NAME, "playerName is required");
        String content = call.param("content", "");
        if (content.isBlank()) return ToolResult.fail(NAME, "content is required");
        String summary = call.param("summary", "");

        try {
            String actId = dm.generateNextActId(branchId);
            PlayerActionRecord record = PlayerActionRecord.create(
                    actId, branchId, playerName, content, summary, "active", "agent");

            String markdown = dm.readBranchFile(branchId);
            String newMarkdown = BranchFileSimContent.appendPlayerAction(markdown, record);
            dm.writeBranchFile(branchId, newMarkdown, "append_" + actId);

            String filePath = dm.getBranchFilePath(branchId).toString();
            log.info("Appended {} to branch {}", actId, branchId);

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item(playerName + "：…", actId,
                            "actId=" + actId + " branchId=" + branchId + " filePath=" + filePath
                                    + " playerName=" + playerName,
                            1.0)));
        } catch (Exception e) {
            log.error("player_action_append failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "APPEND_FAILED: " + e.getMessage());
        }
    }
}
