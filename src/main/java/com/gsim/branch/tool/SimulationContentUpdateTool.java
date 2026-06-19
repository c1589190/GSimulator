package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * simulation_content_update — 更新指定推演内容。
 */
public class SimulationContentUpdateTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SimulationContentUpdateTool.class);
    public static final String NAME = "simulation_content_update";

    private final DataManager dm;

    public SimulationContentUpdateTool(DataManager dm) {
        this.dm = dm;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "更新指定推演内容。参数: branchId (可选，默认current), simId (必填), "
                + "title (可选), content (可选), summary (可选), status (可选, draft|active|superseded)。"
                + "仅更新提供的字段，未提供的保持不变。";
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

        String simId = call.param("simId", "");
        if (simId.isBlank()) return ToolResult.fail(NAME, "simId is required");

        String newTitle = call.param("title", null);
        String newContent = call.param("content", null);
        String newSummary = call.param("summary", null);
        String newStatus = call.param("status", null);

        try {
            String markdown = dm.readBranchFile(branchId);
            String newMarkdown = BranchFileSimContent.updateSimContent(
                    markdown, simId, newTitle, newContent, newSummary, newStatus);
            dm.writeBranchFile(branchId, newMarkdown, "update_" + simId);

            log.info("Updated {} in branch {}", simId, branchId);

            StringBuilder sb = new StringBuilder();
            sb.append("status=OK simId=").append(simId).append(" branchId=").append(branchId);
            if (newTitle != null) sb.append(" title_updated=true");
            if (newContent != null) sb.append(" content_updated=true");
            if (newSummary != null) sb.append(" summary_updated=true");
            if (newStatus != null) sb.append(" status=").append(newStatus);

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item(simId, branchId, sb.toString(), 1.0)));
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("SIM_CONTENT_NOT_FOUND")) {
                return ToolResult.fail(NAME, msg);
            }
            log.error("simulation_content_update failed: {}", msg);
            return ToolResult.fail(NAME, "UPDATE_FAILED: " + msg);
        }
    }
}
