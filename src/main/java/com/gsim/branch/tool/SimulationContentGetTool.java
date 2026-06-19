package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * simulation_content_get — 读取指定推演内容的全文。
 */
public class SimulationContentGetTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SimulationContentGetTool.class);
    public static final String NAME = "simulation_content_get";

    private final DataManager dm;

    public SimulationContentGetTool(DataManager dm) {
        this.dm = dm;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "读取指定推演内容的全文。参数: branchId (可选，默认current), simId (必填, 如sim0001)。";
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

        try {
            String markdown = dm.readBranchFile(branchId);
            List<SimContentRecord> records = BranchFileSimContent.parseSimContents(markdown, branchId);

            SimContentRecord found = null;
            for (SimContentRecord r : records) {
                if (r.simId().equals(simId)) {
                    found = r;
                    break;
                }
            }

            if (found == null) {
                return ToolResult.fail(NAME, "SIM_CONTENT_NOT_FOUND: " + simId + " in branch " + branchId);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("simId: ").append(found.simId()).append("\n");
            sb.append("branchId: ").append(found.branchId()).append("\n");
            sb.append("type: ").append(found.type()).append("\n");
            sb.append("title: ").append(found.title()).append("\n");
            sb.append("status: ").append(found.status()).append("\n");
            sb.append("source: ").append(found.source()).append("\n");
            sb.append("createdAt: ").append(found.createdAt()).append("\n\n");
            sb.append(found.content());

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item(found.title(), found.simId(), sb.toString(), 1.0)));
        } catch (Exception e) {
            log.error("simulation_content_get failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "GET_FAILED: " + e.getMessage());
        }
    }
}
