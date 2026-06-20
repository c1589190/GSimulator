package com.gsim.branch.tool;

import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * simulation_content_append — 向当前 active branch 追加一条推演内容。
 */
public class SimulationContentAppendTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SimulationContentAppendTool.class);
    public static final String NAME = "simulation_content_append";

    private final DataManager dm;

    public SimulationContentAppendTool(DataManager dm) {
        this.dm = dm;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "向当前 active branch 追加一条推演内容。参数: branchId (可选，默认当前active branch), "
                + "type (prologue|scene|event|dialogue|battle|policy|economy|investigation|settlement_draft|other), "
                + "title (必填), content (必填), summary (可选), status (draft|active|superseded, 默认draft), "
                + "metadata (可选JSON), revisionOf (可选, 重推时指向旧simId，默认无)。"
                + "返回 simId, branchId, filePath。重推不覆盖旧版，追加新版。";
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

        String type = call.param("type", "other");
        String title = call.param("title", "");
        if (title.isBlank()) return ToolResult.fail(NAME, "title is required");
        String content = call.param("content", "");
        if (content.isBlank()) return ToolResult.fail(NAME, "content is required");
        String summary = call.param("summary", "");
        String status = call.param("status", "draft");
        String metadata = call.param("metadata", "");
        String revisionOf = call.param("revisionOf", "");

        try {
            // 生成 simId
            String simId = dm.generateNextSimId(branchId);

            // 创建记录
            SimContentRecord record;
            if (revisionOf != null && !revisionOf.isBlank()) {
                record = SimContentRecord.createRevision(
                        simId, branchId, type, title, content, summary, status, "agent", metadata, revisionOf);
            } else {
                record = SimContentRecord.create(
                        simId, branchId, type, title, content, summary, status, "agent", metadata);
            }

            // 读取当前 branch 文件
            String markdown = dm.readBranchFile(branchId);

            // 追加 sim content（不覆盖旧版）
            String newMarkdown = BranchFileSimContent.appendSimContent(markdown, record);

            // 写回
            dm.writeBranchFile(branchId, newMarkdown, "append_" + simId);

            String filePath = dm.getBranchFilePath(branchId).toString();
            String revInfo = (revisionOf != null && !revisionOf.isBlank()) ? " revisionOf=" + revisionOf : "";
            log.info("Appended {} to branch {}{}", simId, branchId, revInfo);

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item(title, simId,
                            "simId=" + simId + " branchId=" + branchId + " filePath=" + filePath
                                    + " type=" + type + " status=" + status + revInfo,
                            1.0),
                    new ToolResult.Item("simulation_content_text", "",
                            "# " + title + "\n\n" + content,
                            1.0)));
        } catch (Exception e) {
            log.error("simulation_content_append failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "APPEND_FAILED: " + e.getMessage());
        }
    }
}
