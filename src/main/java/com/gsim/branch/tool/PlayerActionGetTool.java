package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * player_action_get — 读取指定玩家行动的全文。
 */
public class PlayerActionGetTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerActionGetTool.class);
    public static final String NAME = "player_action_get";

    private final DataManager dm;

    public PlayerActionGetTool(DataManager dm) {
        this.dm = dm;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "读取指定玩家行动的全文。参数: branchId (可选，默认current), actId (必填, 如act0001)。";
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

        String actId = call.param("actId", "");
        if (actId.isBlank()) return ToolResult.fail(NAME, "actId is required");

        try {
            String markdown = dm.readBranchFile(branchId);
            List<PlayerActionRecord> records = BranchFileSimContent.parsePlayerActions(markdown, branchId);

            PlayerActionRecord found = null;
            for (PlayerActionRecord r : records) {
                if (r.actId().equals(actId)) {
                    found = r;
                    break;
                }
            }

            if (found == null) {
                return ToolResult.fail(NAME, "PLAYER_ACTION_NOT_FOUND: " + actId + " in branch " + branchId);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("actId: ").append(found.actId()).append("\n");
            sb.append("branchId: ").append(found.branchId()).append("\n");
            sb.append("playerName: ").append(found.playerName()).append("\n");
            sb.append("status: ").append(found.status()).append("\n");
            sb.append("createdAt: ").append(found.createdAt()).append("\n");
            if (found.revisionOf() != null && !found.revisionOf().isBlank()) {
                sb.append("revisionOf: ").append(found.revisionOf()).append("\n");
            }
            sb.append("\n").append(found.content());

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item(found.playerName() + " — " + found.actId(),
                            found.actId(), sb.toString(), 1.0)));
        } catch (Exception e) {
            log.error("player_action_get failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "GET_FAILED: " + e.getMessage());
        }
    }
}
