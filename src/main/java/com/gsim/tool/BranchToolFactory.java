package com.gsim.tool;

import com.gsim.data.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Branch Tool 工厂 — 创建分支管理工具，供 Agent 在 ToolLoop 中调用。
 *
 * <p>提供的工具：
 * <ul>
 *   <li>branch_create_child — 从当前 branch 创建子节点，切换 active branch</li>
 *   <li>branch_switch — 切换到已有 branch</li>
 * </ul>
 */
public class BranchToolFactory {

    private static final Logger log = LoggerFactory.getLogger(BranchToolFactory.class);

    private final DataManager dm;
    private final Consumer<String> onBranchChanged;

    public BranchToolFactory(DataManager dm, Consumer<String> onBranchChanged) {
        this.dm = dm;
        this.onBranchChanged = onBranchChanged;
    }

    public List<AgentTool> createAll() {
        return List.of(
                new BranchCreateChildTool(),
                new BranchSwitchTool()
        );
    }

    // ===== branch_create_child =====

    private class BranchCreateChildTool implements AgentTool {
        @Override
        public String name() { return "branch_create_child"; }

        @Override
        public String description() {
            return "从当前 branch 创建子节点并切换到新 branch。参数: title(必填, 节点名称如'第一回合：罗德岛抵达边境'), " +
                   "initialInput(可选, 本节点的输入/前情/开场叙述), worldTime(可选, 游戏内时间)。" +
                   "创建后 activeBranch 自动切换到新节点，ContextSession 将重建。";
        }

        @Override
        public ToolResult execute(ToolCall call) {
            if (!dm.hasActiveRoot()) {
                return ToolResult.fail(name(), "NO_ACTIVE_ROOT: 没有 active root，无法创建 branch。");
            }

            String title = call.param("title", "");
            if (title.isBlank()) {
                return ToolResult.fail(name(), "title is required");
            }

            String initialInput = call.param("initialInput", "");
            String worldTime = call.param("worldTime", "");

            // 从 title 生成 branch ID
            String rawId = titleToBranchId(title);

            try {
                // 如果有 initialInput，先写入 input.md 以便 createBranch 读取
                if (!initialInput.isBlank()) {
                    dm.appendInput(initialInput);
                }

                String parentBefore = dm.getActiveBranchId();
                var doc = dm.createBranch(rawId, title,
                        worldTime.isBlank() ? null : worldTime);

                if (onBranchChanged != null) {
                    onBranchChanged.accept(doc.id());
                }

                String snippet = "branchId=" + doc.id()
                        + " parent=" + parentBefore
                        + " activeBranch=" + dm.getActiveBranchId()
                        + " isAtRoot=" + dm.isAtRootBranch()
                        + " title=" + title;
                if (!worldTime.isBlank()) snippet += " worldTime=" + worldTime;

                log.info("branch_create_child: {} → {} (parent={})", title, doc.id(), parentBefore);
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item(title, doc.id(), snippet, 1.0)));
            } catch (IOException e) {
                log.error("branch_create_child failed: {}", e.getMessage(), e);
                return ToolResult.fail(name(), "BRANCH_CREATE_FAILED: " + e.getMessage());
            }
        }

        /**
         * 从中文标题提取简短的 branch ID。
         * "第一回合：罗德岛抵达边境" → branch.b-first-turn
         */
        private String titleToBranchId(String title) {
            String base = title.replaceAll("[：:].*", "").trim();
            if (base.length() > 30) {
                base = base.substring(0, 30);
            }
            // 拼音化简化: 取标题的主体部分
            String alpha = base
                    .replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "-")
                    .replaceAll("-+", "-")
                    .replaceAll("(^-|-$)", "");
            if (alpha.length() > 40) alpha = alpha.substring(0, 40);
            // 确保以 branch. 前缀
            return DataManager.normalizeBranchId(alpha);
        }
    }

    // ===== branch_switch =====

    private class BranchSwitchTool implements AgentTool {
        @Override
        public String name() { return "branch_switch"; }

        @Override
        public String description() {
            return "切换到已有 branch。参数: branchId(必填, 如 branch.b0001-first-turn 或 b0001-first-turn)。" +
                   "切换后 ContextSession 将重建。";
        }

        @Override
        public ToolResult execute(ToolCall call) {
            if (!dm.hasActiveRoot()) {
                return ToolResult.fail(name(), "NO_ACTIVE_ROOT");
            }

            String branchId = call.param("branchId", "");
            if (branchId.isBlank()) {
                return ToolResult.fail(name(), "branchId is required");
            }

            try {
                dm.switchBranch(branchId);
                if (onBranchChanged != null) {
                    onBranchChanged.accept(dm.getActiveBranchId());
                }
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item("switched to " + dm.getActiveBranchId(),
                                dm.getActiveBranchId(),
                                "activeBranch=" + dm.getActiveBranchId()
                                        + " isAtRoot=" + dm.isAtRootBranch(), 1.0)));
            } catch (IOException e) {
                return ToolResult.fail(name(), "BRANCH_SWITCH_FAILED: " + e.getMessage());
            }
        }
    }
}
