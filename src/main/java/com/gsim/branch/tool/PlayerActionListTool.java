package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * player_action_list — 列出当前 active branch 中所有玩家行动记录。
 */
public class PlayerActionListTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerActionListTool.class);
    public static final String NAME = "player_action_list";

    private final DataManager dm;

    public PlayerActionListTool(DataManager dm) {
        this.dm = dm;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "列出当前 active branch 中的所有玩家行动记录。参数: branchId (可选，默认current)。"
                + "返回 actId, playerName, status, summary, revisionOf。";
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

        try {
            String markdown = dm.readBranchFile(branchId);
            List<PlayerActionRecord> records = BranchFileSimContent.parsePlayerActions(markdown, branchId);

            StringBuilder sb = new StringBuilder();
            sb.append("branchId: ").append(branchId).append("\n");
            sb.append("count: ").append(records.size()).append("\n");
            if (records.isEmpty()) {
                sb.append("（当前节点没有玩家行动记录）\n");
            } else {
                for (PlayerActionRecord r : records) {
                    sb.append("\n--- ").append(r.actId()).append(" ---\n");
                    sb.append("playerName: ").append(r.playerName()).append("\n");
                    sb.append("status: ").append(r.status()).append("\n");
                    sb.append("createdAt: ").append(r.createdAt()).append("\n");
                    if (r.summary() != null && !r.summary().isBlank()) {
                        sb.append("summary: ").append(r.summary()).append("\n");
                    }
                    if (r.revisionOf() != null && !r.revisionOf().isBlank()) {
                        sb.append("revisionOf: ").append(r.revisionOf()).append("\n");
                    }
                    // 截取前 100 字符供预览
                    String preview = r.content();
                    if (preview.length() > 100) preview = preview.substring(0, 97) + "...";
                    sb.append("preview: ").append(preview).append("\n");
                }
            }

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("Player Actions for " + branchId, branchId,
                            sb.toString(), 1.0)));
        } catch (Exception e) {
            log.error("player_action_list failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "LIST_FAILED: " + e.getMessage());
        }
    }
}
