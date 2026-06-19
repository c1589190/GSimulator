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
                + "settlementId (可选, 指定读取某个历史版本的完整正文，如 stl0001)。"
                + "不传 settlementId 时返回 settlementId 列表、revisionOf 关系、最新结算全文。"
                + "传入 settlementId 时返回该版本的完整正文、revisionOf、referencedSimIds、referencedActionIds、inputSummary。";
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
            String nodeOverview = BranchFileSimContent.readNodeOverview(markdown);
            String requestedSettlementId = call.param("settlementId", "");

            StringBuilder sb = new StringBuilder();

            if (!requestedSettlementId.isBlank()) {
                // === 按 settlementId 读取特定版本 ===
                BranchFileSimContent.SettlementRecord found = null;
                for (BranchFileSimContent.SettlementRecord s : settlements) {
                    if (s.settlementId().equals(requestedSettlementId)) {
                        found = s;
                        break;
                    }
                }
                if (found == null) {
                    return ToolResult.fail(NAME, "SETTLEMENT_NOT_FOUND: " + requestedSettlementId
                            + " in branch " + branchId);
                }

                sb.append("branchId: ").append(branchId).append("\n");
                sb.append("settlementId: ").append(found.settlementId()).append("\n");
                if (found.revisionOf() != null && !found.revisionOf().isBlank()) {
                    sb.append("revisionOf: ").append(found.revisionOf()).append("\n");
                }
                if (found.inputSummary() != null && !found.inputSummary().isBlank()) {
                    sb.append("inputSummary: ").append(found.inputSummary()).append("\n");
                }
                if (found.referencedSimIds() != null && !found.referencedSimIds().isBlank()) {
                    sb.append("referencedSimIds: ").append(found.referencedSimIds()).append("\n");
                }
                if (found.referencedActionIds() != null && !found.referencedActionIds().isBlank()) {
                    sb.append("referencedActionIds: ").append(found.referencedActionIds()).append("\n");
                }
                sb.append("\n--- 完整结算 ---\n\n");
                sb.append(found.settlement());
            } else {
                // === 现有行为：返回列表 + 最新 ===
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

                    String latestSettlement = BranchFileSimContent.readTurnSettlement(markdown);
                    sb.append("\n--- 最新结算 (")
                            .append(settlements.get(settlements.size() - 1).settlementId())
                            .append(") ---\n\n");
                    sb.append(latestSettlement);
                }
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
