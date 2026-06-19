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
 * branch_goto_parent — 从当前 active branch 切换到父节点。
 * 返回父节点的轻量状态信息（NodeOverview、counts、parent/children）。
 */
public class BranchGotoParentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(BranchGotoParentTool.class);
    public static final String NAME = "branch_goto_parent";

    private final DataManager dm;
    private final Runnable onBranchChanged;

    public BranchGotoParentTool(DataManager dm, Runnable onBranchChanged) {
        this.dm = dm;
        this.onBranchChanged = onBranchChanged;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "从当前 active branch 切换到父节点。不需要参数（直接读取当前 branch 的 parent front matter）。"
                + "返回父节点的 NodeOverview、actionCount、simContentCount、settlementCount、parent/children 列表。"
                + "如果当前已在根节点（parent=none），返回错误。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!dm.hasActiveRoot()) {
            return ToolResult.fail(NAME, "NO_ACTIVE_ROOT");
        }

        String currentBranch = dm.getActiveBranchId();
        DataDocument currentDoc = dm.readById(currentBranch);
        if (currentDoc == null) {
            return ToolResult.fail(NAME, "CURRENT_BRANCH_NOT_FOUND: " + currentBranch);
        }

        String parentId = currentDoc.frontMatter().getOrDefault("parent", "none");
        if ("none".equals(parentId)) {
            return ToolResult.fail(NAME, "ALREADY_AT_ROOT: 当前已在根节点，没有父节点。");
        }

        try {
            dm.switchBranch(parentId);
            if (onBranchChanged != null) {
                onBranchChanged.run();
            }

            // 读取父节点轻量状态
            String markdown = dm.readBranchFile(parentId);
            BranchFileSimContent.NodeLightStatus status =
                    BranchFileSimContent.getNodeLightStatus(markdown);

            DataDocument parentDoc = dm.readById(parentId);
            List<DataDocument> children = dm.getChildBranches(parentId);
            String grandparent = parentDoc != null
                    ? parentDoc.frontMatter().getOrDefault("parent", "none") : "none";

            StringBuilder sb = new StringBuilder();
            sb.append("status=OK switchedTo=").append(parentId).append("\n");
            sb.append("previousBranch=").append(currentBranch).append("\n");
            sb.append("parentOfThis=").append(grandparent).append("\n");
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

            log.info("Switched to parent branch {} from {}", parentId, currentBranch);

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("Parent: " + parentId, parentId, sb.toString(), 1.0)));
        } catch (IOException e) {
            log.error("branch_goto_parent failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "GOTO_PARENT_FAILED: " + e.getMessage());
        }
    }
}
