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
 * branch_next_turn — 创建下一回合节点并切换 active branch（原子操作）。
 *
 * <p>等价于 CLI {@code /nextturn <世界时间> [备注]}。
 * <ul>
 *   <li>基于当前 activeBranch 创建子节点</li>
 *   <li>parent = 当前 activeBranch</li>
 *   <li>turn = parent.turn + 1</li>
 *   <li>创建成功后自动切换 activeBranch 到新节点</li>
 *   <li>触发 onBranchChanged / ContextSession reset</li>
 * </ul>
 *
 * <p>与 {@code branch_create_child} 的区别：
 * <ul>
 *   <li>branch_create_child — 只创建节点，语义上不保证状态完整切换</li>
 *   <li>branch_next_turn — 创建并进入下一回合，一步完成，返回完整状态</li>
 * </ul>
 */
public class BranchNextTurnTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(BranchNextTurnTool.class);
    public static final String NAME = "branch_next_turn";

    private final DataManager dm;
    private final Runnable onBranchChanged;

    public BranchNextTurnTool(DataManager dm, Runnable onBranchChanged) {
        this.dm = dm;
        this.onBranchChanged = onBranchChanged;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "创建下一回合节点并切换 active branch（原子操作，等价于 /nextturn）。"
                + "当你需要「进入下一回合」「创建下一节点」「下一回合开始」时，必须使用此工具。"
                + "参数: worldTime (必填, 游戏内时间如'泰拉纪年1096年冬'), "
                + "title (可选, 节点名称), initialInput (可选, 初始输入/开场叙事), "
                + "note (可选, 备注)。"
                + "创建后自动切换 activeBranch 到新节点，返回 switched=true。"
                + "不要只调用 branch_create_child 后就声称已进入下一回合——必须调用此工具。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!dm.hasActiveRoot()) {
            return ToolResult.fail(NAME, "NO_ACTIVE_ROOT: 没有 active root，无法创建下一回合。");
        }

        String worldTime = call.param("worldTime", "");
        String title = call.param("title", "");
        String initialInput = call.param("initialInput", "");
        String note = call.param("note", "");

        if (worldTime.isBlank()) {
            return ToolResult.fail(NAME, "worldTime is required — 下一回合必须有游戏内时间。");
        }

        // note 优先于 initialInput
        String effectiveNote = !note.isBlank() ? note : initialInput;

        try {
            String parentBeforeCreate = dm.getActiveBranchId();

            // 检查父节点是否有推演结果（与 CLI /nextturn 行为一致）
            boolean hasSimResult = dm.hasSimulationResult(parentBeforeCreate);
            String warning = hasSimResult ? "" : "NO_SIM_RESULT";

            DataDocument newDoc = dm.createNextTurnBranch(worldTime, effectiveNote);

            String createdBranchId = newDoc.id();
            String activeBranchId = dm.getActiveBranchId();
            String parentBranchId = newDoc.frontMatter().getOrDefault("parent", parentBeforeCreate);
            String turn = newDoc.frontMatter().getOrDefault("turn", "?");

            // 如果指定了 title，覆盖默认的 "时间节点 <branchId>"
            if (!title.isBlank()) {
                try {
                    String current = dm.readBranchFile(createdBranchId);
                    String updatedName = current.replaceFirst(
                            "name: " + java.util.regex.Pattern.quote(newDoc.name()),
                            "name: " + title);
                    dm.writeBranchFile(createdBranchId, updatedName, "rename_next_turn");
                } catch (Exception renameEx) {
                    log.warn("Failed to rename branch {} to '{}': {}", createdBranchId, title, renameEx.getMessage());
                }
            }

            // 触发 onBranchChanged（ContextSession reset）
            if (onBranchChanged != null) {
                onBranchChanged.run();
            }

            // 构建详细返回
            StringBuilder snippet = new StringBuilder();
            snippet.append("status=OK\n");
            snippet.append("createdBranchId=").append(createdBranchId).append("\n");
            snippet.append("parentBranchId=").append(parentBranchId).append("\n");
            snippet.append("activeBranchId=").append(activeBranchId).append("\n");
            snippet.append("turn=").append(turn).append("\n");
            snippet.append("worldTime=").append(worldTime).append("\n");
            snippet.append("switched=true\n");
            if (!hasSimResult) {
                snippet.append("warning=NO_SIM_RESULT (父节点尚未推演，仍创建了子节点)\n");
            }

            log.info("branch_next_turn: {} → {} (parent={}, turn={})",
                    parentBeforeCreate, createdBranchId, parentBranchId, turn);

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("下一回合: " + createdBranchId,
                            createdBranchId,
                            snippet.toString(),
                            1.0)));
        } catch (Exception e) {
            log.error("branch_next_turn failed: {}", e.getMessage(), e);
            return ToolResult.fail(NAME, "NEXT_TURN_FAILED: " + e.getMessage());
        }
    }
}
