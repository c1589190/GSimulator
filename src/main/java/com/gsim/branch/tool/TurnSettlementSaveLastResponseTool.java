package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * turn_settlement_save_last_response — 使用缓存的 lastAssistantDraft 保存回合结算。
 *
 * <p><strong>解决问题：</strong>长篇 settlement 正文无法可靠通过 raw JSON 工具调用传输
 * （第三方 API 下降级、fenced JSON 泄露、转义失败、截断）。
 *
 * <p>此工具的 JSON 参数必须很短，settlement 正文从 OrchestratorAgent 的
 * lastAssistantDraft 缓存中读取。
 *
 * <p>参数：
 * <ul>
 *   <li>branchId — 可选，默认当前 activeBranch</li>
 *   <li>inputSummary — 本回合输入摘要（短文本）</li>
 *   <li>title — 可选，结算标题</li>
 * </ul>
 */
public class TurnSettlementSaveLastResponseTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(TurnSettlementSaveLastResponseTool.class);
    public static final String NAME = "turn_settlement_save_last_response";

    private final DataManager dm;
    private final Supplier<String> draftSupplier;

    public TurnSettlementSaveLastResponseTool(DataManager dm, Supplier<String> draftSupplier) {
        this.dm = dm;
        this.draftSupplier = draftSupplier;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "使用你刚生成的的长篇结算正文保存回合结算。参数非常短，不需要长文本。"
                + "参数: branchId (可选，默认current), inputSummary (可选，本回合输入摘要), title (可选)。"
                + "系统自动使用你上一条自然语言回复（已自动剥离 raw JSON/[工具结果]/系统提示）作为 settlement 正文。"
                + "保存成功后返回 settlementId。"
                + "如果你刚生成的长篇结算正文需要保存，优先使用本工具，不要使用 turn_settlement_save。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!dm.hasActiveRoot()) {
            return ToolResult.fail(NAME, "NO_ACTIVE_ROOT");
        }

        // 1. 读取缓存的草稿
        String draft = draftSupplier.get();
        if (draft == null || draft.isBlank()) {
            return ToolResult.fail(NAME, "NO_LAST_ASSISTANT_DRAFT: "
                    + "没有缓存的 assistant 回复。请先生成结算正文（自然语言），再调用本工具保存。");
        }

        // 2. 解析参数
        String branchIdParam = call.param("branchId", "current");
        String branchId;
        if ("current".equals(branchIdParam)) {
            branchId = dm.getActiveBranchId();
            if (branchId == null) return ToolResult.fail(NAME, "NO_ACTIVE_BRANCH");
        } else {
            branchId = DataManager.normalizeBranchId(branchIdParam);
        }

        String inputSummary = call.param("inputSummary", "");
        String title = call.param("title", "");

        // 如果提供了 title，在 settlement 正文前添加标题行
        String settlement = draft;
        if (!title.isBlank()) {
            settlement = "## " + title + "\n\n" + draft;
        }

        try {
            // 3. 使用与 turn_settlement_save 相同的保存逻辑
            String markdown = dm.readBranchFile(branchId);
            String settlementId = dm.generateNextSettlementId(branchId);

            String newMarkdown = BranchFileSimContent.saveTurnSettlement(
                    markdown, settlementId,
                    null, // revisionOf — 默认不重推
                    inputSummary, settlement,
                    null, null, null, null, null, // deltas
                    "", "" // referenced IDs
            );

            dm.writeBranchFile(branchId, newMarkdown, "turn_settlement_" + settlementId);

            StringBuilder sb = new StringBuilder();
            sb.append("status=OK\n");
            sb.append("settlementId=").append(settlementId).append("\n");
            sb.append("branchId=").append(branchId).append("\n");
            sb.append("savedFrom=lastAssistantDraft\n");
            sb.append("draftLength=").append(draft.length()).append("\n");
            sb.append("filePath=").append(dm.getBranchFilePath(branchId)).append("\n");
            sb.append("updatedSections=TURN_SETTLEMENT, NODE_OVERVIEW\n");
            if (!inputSummary.isBlank()) {
                sb.append("inputSummary=").append(inputSummary).append("\n");
            }

            log.info("Saved turn settlement {} via last_response for branch {} (draft={} chars)",
                    settlementId, branchId, draft.length());

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("Turn Settlement " + settlementId, branchId,
                            sb.toString(), 1.0)));
        } catch (Exception e) {
            log.error("turn_settlement_save_last_response failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "SAVE_FAILED: " + e.getMessage());
        }
    }
}
