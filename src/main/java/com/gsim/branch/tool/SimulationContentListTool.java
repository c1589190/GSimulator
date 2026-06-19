package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * simulation_content_list — 列出当前 active branch 中所有推演内容。
 */
public class SimulationContentListTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SimulationContentListTool.class);
    public static final String NAME = "simulation_content_list";

    private final DataManager dm;

    public SimulationContentListTool(DataManager dm) {
        this.dm = dm;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "列出当前 active branch 中的所有推演内容条目。参数: branchId (可选，默认current)。"
                + "返回 simId, type, title, status, summary。";
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
            List<SimContentRecord> records = BranchFileSimContent.parseSimContents(markdown, branchId);

            StringBuilder sb = new StringBuilder();
            sb.append("branchId: ").append(branchId).append("\n");
            sb.append("count: ").append(records.size()).append("\n");
            if (records.isEmpty()) {
                sb.append("（当前节点没有推演内容记录）\n");
            } else {
                for (SimContentRecord r : records) {
                    sb.append("\n--- ").append(r.simId()).append(" ---\n");
                    sb.append("type: ").append(r.type()).append("\n");
                    sb.append("title: ").append(r.title()).append("\n");
                    sb.append("status: ").append(r.status()).append("\n");
                    sb.append("createdAt: ").append(r.createdAt()).append("\n");
                    if (r.summary() != null && !r.summary().isBlank()) {
                        sb.append("summary: ").append(r.summary()).append("\n");
                    }
                }
            }

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("Sim Contents for " + branchId, branchId,
                            sb.toString(), 1.0)));
        } catch (Exception e) {
            log.error("simulation_content_list failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "LIST_FAILED: " + e.getMessage());
        }
    }
}
