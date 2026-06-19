package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * player_action_update — 修订玩家行动（追加新版，不覆盖旧版）。
 *
 * 不允许原地覆盖旧 action。修订时：
 * - 如果提供了 revisionOf，追加新 actId 并设置 revisionOf 指向旧 action
 * - 旧 action 保持不变
 * - 仅当用户明确要求"作废"时才标记旧 action 为 superseded
 */
public class PlayerActionUpdateTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerActionUpdateTool.class);
    public static final String NAME = "player_action_update";

    private final DataManager dm;

    public PlayerActionUpdateTool(DataManager dm) {
        this.dm = dm;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "修订玩家行动（追加新版，不删除旧版，不覆盖旧版）。参数: branchId (可选，默认current), "
                + "actId (必填，要修订的旧行动ID), playerName (必填), content (必填), summary (可选), "
                + "supersedeOld (可选, true=标记旧版为superseded, 默认false=旧版保持active)。"
                + "会追加新 actId 并设 revisionOf=旧actId。旧版原文永远保留。";
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

        String oldActId = call.param("actId", "");
        if (oldActId.isBlank()) return ToolResult.fail(NAME, "actId is required");
        String playerName = call.param("playerName", "");
        if (playerName.isBlank()) return ToolResult.fail(NAME, "playerName is required");
        String content = call.param("content", "");
        if (content.isBlank()) return ToolResult.fail(NAME, "content is required");
        String summary = call.param("summary", "");
        boolean supersedeOld = "true".equalsIgnoreCase(call.param("supersedeOld", "false"));

        try {
            String markdown = dm.readBranchFile(branchId);

            // 验证旧 action 存在
            List<PlayerActionRecord> records = BranchFileSimContent.parsePlayerActions(markdown, branchId);
            boolean oldExists = records.stream().anyMatch(r -> r.actId().equals(oldActId));
            if (!oldExists) {
                return ToolResult.fail(NAME, "PLAYER_ACTION_NOT_FOUND: " + oldActId);
            }

            // 追加新 action（revision）
            String newActId = dm.generateNextActId(branchId);
            PlayerActionRecord newRecord = PlayerActionRecord.createRevision(
                    newActId, branchId, playerName, content, summary,
                    supersedeOld ? "active" : "active", "agent", oldActId);

            markdown = BranchFileSimContent.appendPlayerAction(markdown, newRecord);

            // 如果要求作废旧版
            if (supersedeOld) {
                try {
                    markdown = BranchFileSimContent.updatePlayerActionMeta(
                            markdown, oldActId, "superseded", null);
                } catch (Exception e) {
                    log.warn("Failed to supersede old action {}: {}", oldActId, e.getMessage());
                }
            }

            dm.writeBranchFile(branchId, markdown, "revision_" + oldActId + "_as_" + newActId);

            String filePath = dm.getBranchFilePath(branchId).toString();
            log.info("Revised PlayerAction {} as {} in branch {}", oldActId, newActId, branchId);

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item(playerName + "（修订）", newActId,
                            "status=OK newActId=" + newActId
                                    + " revisionOf=" + oldActId
                                    + " branchId=" + branchId
                                    + " filePath=" + filePath
                                    + (supersedeOld ? " oldActionSuperseded=true" : ""),
                            1.0)));
        } catch (Exception e) {
            log.error("player_action_update failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "UPDATE_FAILED: " + e.getMessage());
        }
    }
}
