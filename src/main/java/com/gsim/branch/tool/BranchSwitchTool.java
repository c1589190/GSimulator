package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * branch_switch — 切换当前 active branch。
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
        return "切换到指定 branch。参数: branchId (必填, 如 b0001 或 branch.b0001-first-turn)。";
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

            log.info("Switched branch {} -> {}", oldBranch, dm.getActiveBranchId());

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("Switched to " + dm.getActiveBranchId(),
                            dm.getActiveBranchId(),
                            "status=OK previousBranch=" + oldBranch
                                    + " activeBranch=" + dm.getActiveBranchId()
                                    + " isAtRoot=" + dm.isAtRootBranch(),
                            1.0)));
        } catch (Exception e) {
            log.error("branch_switch failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "SWITCH_FAILED: " + e.getMessage());
        }
    }
}
