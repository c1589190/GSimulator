package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * turn_settlement_get — 读取当前 branch 的回合结算列表和最新结算正文。
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
        return "读取当前 branch 的所有回合结算列表和最新结算正文。参数: branchId (可选，默认current)。"
                + "返回 settlementId 列表、revisionOf 关系、最新结算全文。";
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
            List<BranchFileSimContent.SettlementRecord> settlements =
                    BranchFileSimContent.parseSettlements(markdown);
            String latestSettlement = BranchFileSimContent.readTurnSettlement(markdown);
            String nodeOverview = BranchFileSimContent.readNodeOverview(markdown);

            StringBuilder sb = new StringBuilder();
            sb.append("branchId: ").append(branchId).append("\n");
            sb.append("settlementCount: ").append(settlements.size()).append("\n");

            if (settlements.isEmpty()) {
                sb.append("（尚无回合结算）\n");
            } else {
                sb.append("\n--- 结算列表 ---\n");
                for (BranchFileSimContent.SettlementRecord s : settlements) {
                    sb.append(s.settlementId());
                    if (s.revisionOf() != null && !s.revisionOf().isBlank()) {
                        sb.append(" (revisionOf: ").append(s.revisionOf()).append(")");
                    }
                    sb.append("\n");
                }

                sb.append("\n--- 最新结算 (")
                        .append(settlements.get(settlements.size() - 1).settlementId())
                        .append(") ---\n\n");
                sb.append(latestSettlement);
            }

            if (!nodeOverview.isBlank()) {
                sb.append("\n\n--- 节点概览 ---\n");
                sb.append(nodeOverview);
            }

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("Turn Settlements: " + branchId, branchId,
                            sb.toString(), 1.0)));
        } catch (Exception e) {
            log.error("turn_settlement_get failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "GET_FAILED: " + e.getMessage());
        }
    }
}
