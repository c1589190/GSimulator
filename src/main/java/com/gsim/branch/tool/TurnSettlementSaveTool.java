package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * turn_settlement_save — 保存当前回合最终结算到 branch 文件。
 * 追加新版本，不覆盖旧版。同步更新 NODE_OVERVIEW。
 */
public class TurnSettlementSaveTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(TurnSettlementSaveTool.class);
    public static final String NAME = "turn_settlement_save";

    private final DataManager dm;

    public TurnSettlementSaveTool(DataManager dm) {
        this.dm = dm;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "保存当前回合的最终结算到 branch 文件（追加新版本，不覆盖旧版）。参数: branchId (可选，默认current), "
                + "inputSummary (本回合输入摘要), settlement (完整回合结算正文), "
                + "worldDelta (世界观/设定增量，可选), entityDelta (实体/势力/人物状态变化，可选), "
                + "ruleDelta (规则变化，可选), interactionDelta (交互逻辑变化，可选), "
                + "risk (下回合风险和钩子，可选), referencedSimIds (逗号分隔的simId列表，可选), "
                + "referencedActionIds (逗号分隔的actId列表，可选), "
                + "revisionOf (重推时指向旧 settlementId，默认无)。"
                + "会同时更新四、五、六、七、九章节和 NODE_OVERVIEW。不会删除旧 settlement。";
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

        String inputSummary = call.param("inputSummary", "");
        String settlement = call.param("settlement", "");
        if (settlement.isBlank()) return ToolResult.fail(NAME, "settlement is required");
        String worldDelta = call.param("worldDelta", null);
        String entityDelta = call.param("entityDelta", null);
        String ruleDelta = call.param("ruleDelta", null);
        String interactionDelta = call.param("interactionDelta", null);
        String risk = call.param("risk", null);
        String referencedSimIds = call.param("referencedSimIds", "");
        String referencedActionIds = call.param("referencedActionIds", "");
        String revisionOf = call.param("revisionOf", "");

        try {
            String markdown = dm.readBranchFile(branchId);

            // 生成 settlementId
            String settlementId = dm.generateNextSettlementId(branchId);

            String newMarkdown = BranchFileSimContent.saveTurnSettlement(
                    markdown, settlementId,
                    revisionOf.isBlank() ? null : revisionOf,
                    inputSummary, settlement, worldDelta, entityDelta,
                    ruleDelta, interactionDelta, risk, referencedSimIds,
                    referencedActionIds.isBlank() ? null : referencedActionIds);

            dm.writeBranchFile(branchId, newMarkdown, "turn_settlement_" + settlementId);

            StringBuilder sb = new StringBuilder();
            sb.append("status=OK settlementId=").append(settlementId).append(" branchId=").append(branchId).append("\n");
            sb.append("filePath=").append(dm.getBranchFilePath(branchId)).append("\n");
            sb.append("updatedSections=");
            java.util.List<String> sections = new java.util.ArrayList<>();
            sections.add("TURN_SETTLEMENT");
            sections.add("NODE_OVERVIEW");
            if (worldDelta != null) sections.add("四、世界观/设定增量");
            if (entityDelta != null) sections.add("五、实体状态增量");
            if (ruleDelta != null) sections.add("六、推演规则增量");
            if (interactionDelta != null) sections.add("七、交互逻辑增量");
            if (risk != null) sections.add("九、下节点风险");
            sb.append(String.join(", ", sections));

            if (!referencedSimIds.isBlank()) {
                sb.append("\nreferencedSimIds=").append(referencedSimIds);
            }
            if (!referencedActionIds.isBlank()) {
                sb.append("\nreferencedActionIds=").append(referencedActionIds);
            }
            if (!revisionOf.isBlank()) {
                sb.append("\nrevisionOf=").append(revisionOf);
            }

            log.info("Saved turn settlement {} for branch {}", settlementId, branchId);

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("Turn Settlement " + settlementId, branchId,
                            sb.toString(), 1.0)));
        } catch (Exception e) {
            log.error("turn_settlement_save failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "SAVE_FAILED: " + e.getMessage());
        }
    }
}
