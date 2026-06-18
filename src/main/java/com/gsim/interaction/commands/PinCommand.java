package com.gsim.interaction.commands;

import com.gsim.context.memory.PinnedConstraint;
import com.gsim.context.memory.PinnedConstraintManager;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;

import java.util.List;

/**
 * /pin — 硬约束管理。
 *
 * <p>子命令：
 * <ul>
 *   <li>/pin list — 列出当前分支所有硬约束</li>
 *   <li>/pin add &lt;内容&gt; — 添加硬约束</li>
 * </ul>
 */
public class PinCommand implements InteractionCommand {

    private final PinnedConstraintManager pinManager;
    private final DataManager dataManager;

    public PinCommand(PinnedConstraintManager pinManager, DataManager dataManager) {
        this.pinManager = pinManager;
        this.dataManager = dataManager;
    }

    @Override
    public String name() {
        return "/pin";
    }

    @Override
    public String description() {
        return "管理硬约束。子命令: list, add <内容>";
    }

    @Override
    public String usage() {
        return "/pin list | /pin add <内容>";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args.length == 0 || "list".equals(args[0])) {
            return handleList();
        }

        if ("add".equals(args[0])) {
            if (args.length < 2) {
                return InteractionResult.fail("用法: /pin add <内容>");
            }
            String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            return handleAdd(text);
        }

        return InteractionResult.fail("未知子命令: " + args[0] + "。可用: list, add");
    }

    private InteractionResult handleList() {
        List<PinnedConstraint> pins = pinManager.listAll();

        if (pins.isEmpty()) {
            return InteractionResult.ok("暂无硬约束。使用 /pin add <内容> 添加。", "（无）");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 硬约束列表 (").append(pins.size()).append(") ===\n\n");
        for (PinnedConstraint pin : pins) {
            sb.append("- [").append(pin.id()).append("] ").append(pin.text()).append("\n");
            sb.append("  branch: ").append(pin.branchId())
                    .append(", from: ").append(pin.sourceNodeId())
                    .append(", by: ").append(pin.createdBy()).append("\n\n");
        }

        return InteractionResult.ok("硬约束列表", sb.toString().trim());
    }

    private InteractionResult handleAdd(String text) {
        String branchId = dataManager.getActiveBranch();
        PinnedConstraint pin = pinManager.addPin(branchId, text, branchId, "user");

        return InteractionResult.ok(
                "硬约束已添加",
                "已添加: " + pin.text() + "\nID: " + pin.id() + "\nbranch: " + pin.branchId()
        );
    }
}
