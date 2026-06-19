package com.gsim.branch.tool;

import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * branch_switch — 切换到已有 branch，返回轻量节点状态。
 */
public class BranchSwitchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(BranchSwitchTool.class);
    public static final String NAME = "branch_switch";

    private final DataManager dm;
    private final Runnable onBranchChanged;

    public BranchSwitchTool(DataManager dm, Runnable onBranchChanged) {
        this.dm = dm;
        this.onBranchChanged = onBranchChanged;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "切换到指定 branch。参数: branchId (必填, 如 b0001 或 branch.b0001-first-turn)。"
                + "切换后返回 NodeOverview、actionCount、simContentCount、settlementCount、parent、children。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!dm.hasActiveRoot()) {
            return ToolResult.fail(NAME, "NO_ACTIVE_ROOT");
        }

        String branchId = call.param("branchId", "");
        if (branchId.isBlank()) return ToolResult.fail(NAME, "branchId is required");

        try {
            String oldBranch = dm.getActiveBranchId();
            dm.switchBranch(branchId);

            if (onBranchChanged != null) {
                onBranchChanged.run();
            }

            // 读取目标节点轻量状态
            String normalizedBranchId = dm.getActiveBranchId();
            String markdown = dm.readBranchFile(normalizedBranchId);
            BranchFileSimContent.NodeLightStatus status =
                    BranchFileSimContent.getNodeLightStatus(markdown);

            DataDocument doc = dm.readById(normalizedBranchId);
            String parent = doc != null
                    ? doc.frontMatter().getOrDefault("parent", "none") : "none";
            List<DataDocument> children = dm.getChildBranches(normalizedBranchId);

            StringBuilder sb = new StringBuilder();
            sb.append("status=OK activeBranch=").append(normalizedBranchId).append("\n");
            sb.append("isAtRoot=").append(dm.isAtRootBranch()).append("\n");
            sb.append("parent=").append(parent).append("\n");
            sb.append("actionCount=").append(status.actionCount()).append("\n");
            sb.append("simContentCount=").append(status.simContentCount()).append("\n");
            sb.append("settlementCount=").append(status.settlementCount()).append("\n");
            sb.append("children=[");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(children.get(i).id());
            }
            sb.append("]\n");
            if (!status.nodeOverview().isBlank()) {
                sb.append("nodeOverview:\n").append(status.nodeOverview()).append("\n");
            } else {
                sb.append("nodeOverview: (无)\n");
            }
            if (!status.latestSettlementSnippet().isBlank()) {
                sb.append("latestSettlement: ").append(status.latestSettlementSnippet()).append("\n");
            }

            log.info("Switched branch {} -> {}", oldBranch, normalizedBranchId);

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("Switched to " + normalizedBranchId,
                            normalizedBranchId, sb.toString(), 1.0)));
        } catch (IOException e) {
            log.error("branch_switch failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "SWITCH_FAILED: " + e.getMessage());
        }
    }
}
