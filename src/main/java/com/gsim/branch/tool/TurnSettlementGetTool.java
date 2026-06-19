package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * turn_settlement_get — 读取当前 branch 的回合结算。
 */
public class TurnSettlementGetTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(TurnSettlementGetTool.class);
    public static final String NAME = "turn_settlement_get";

    private final DataManager dm;

    public TurnSettlementGetTool(DataManager dm) {
        this.dm = dm;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "读取当前 branch 的回合结算（TURN_SETTLEMENT 区块）。参数: branchId (可选，默认current)。";
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
            String settlement = BranchFileSimContent.readTurnSettlement(markdown);

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("Turn Settlement: " + branchId, branchId,
                            "branchId: " + branchId + "\n\n" + settlement, 1.0)));
        } catch (Exception e) {
            log.error("turn_settlement_get failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "GET_FAILED: " + e.getMessage());
        }
    }
}
