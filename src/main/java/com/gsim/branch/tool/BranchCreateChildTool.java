package com.gsim.branch.tool;

import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * branch_create_child — 从当前 active branch 创建子节点。
 *
 * 包装 DataManager.createBranch / createNextTurnBranch。
 */
public class BranchCreateChildTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(BranchCreateChildTool.class);
    public static final String NAME = "branch_create_child";

    private final DataManager dm;
    private final Runnable onBranchChanged;

    public BranchCreateChildTool(DataManager dm, Runnable onBranchChanged) {
        this.dm = dm;
        this.onBranchChanged = onBranchChanged;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "从当前 active branch 创建子节点（下一回合节点）。参数: "
                + "title (可选，默认自动生成), initialInput (可选，初始输入), "
                + "worldTime (可选，世界时间), switchToChild (可选，默认true)。"
                + "创建后自动切换到子节点。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!dm.hasActiveRoot()) {
            return ToolResult.fail(NAME, "NO_ACTIVE_ROOT");
        }

        String title = call.param("title", null);
        String initialInput = call.param("initialInput", "");
        String worldTime = call.param("worldTime", null);
        boolean switchTo = !"false".equalsIgnoreCase(call.param("switchToChild", "true"));

        try {
            // 写入初始输入（如果有）
            if (initialInput != null && !initialInput.isBlank()) {
                dm.appendPlayerInput("system", initialInput);
            }

            // 创建子节点
            DataDocument child = dm.createNextTurnBranch(worldTime, initialInput);

            // 如果指定了 title，更新名称
            if (title != null && !title.isBlank()) {
                // title 在 createNextTurnBranch 中已设置为 "时间节点 <branchId>"
                // 这里通过更新 front matter name 来覆盖
                // 直接用 overwriteBranchSections 保留现有内容，只改 name 不方便
                // 所以先创建再重建
                String current = dm.readBranchFile(child.id());
                String updatedName = current.replaceFirst(
                        "name: " + java.util.regex.Pattern.quote(child.name()),
                        "name: " + title);
                dm.writeBranchFile(child.id(), updatedName, "rename_child");
            }

            // branch changed callback — 可能需要 reset ContextSession
            if (switchTo && onBranchChanged != null) {
                onBranchChanged.run();
            }

            String filePath = dm.getBranchFilePath(child.id()).toString();
            log.info("Created child branch '{}' from parent '{}'", child.id(), dm.getActiveBranchId());

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item(child.name(), child.id(),
                            "status=OK branchId=" + child.id()
                                    + " parentBranchId=" + dm.getActiveBranchId()
                                    + (switchTo ? "" : " (activeBranch not switched)")
                                    + " filePath=" + filePath
                                    + " turn=" + dm.readById(child.id()).frontMatter().getOrDefault("turn", "1"),
                            1.0)));
        } catch (Exception e) {
            log.error("branch_create_child failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "CREATE_CHILD_FAILED: " + e.getMessage());
        }
    }
}
