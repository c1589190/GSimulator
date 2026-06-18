package com.gsim.context.memory;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.List;

/**
 * branch_pin_get — 读取当前分支硬约束。
 */
public class BranchPinGetTool implements AgentTool {

    private final PinnedConstraintManager pinManager;

    public BranchPinGetTool(PinnedConstraintManager pinManager) {
        this.pinManager = pinManager;
    }

    @Override
    public String name() {
        return "branch_pin_get";
    }

    @Override
    public String description() {
        return "读取当前分支硬约束。参数: branchId (可选，默认 current)";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String branchId = call.param("branchId");
        if (branchId == null || "current".equals(branchId)) {
            branchId = "";
        }

        List<PinnedConstraint> pins;
        if (branchId.isEmpty()) {
            pins = pinManager.listAll();
        } else {
            pins = pinManager.getPins(branchId);
        }

        if (pins.isEmpty()) {
            return ToolResult.ok(name(), java.util.List.of(new ToolResult.Item("pins", "", "（暂无硬约束）", 1.0)));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Pinned Constraints (").append(pins.size()).append(") ===\n\n");
        for (PinnedConstraint pin : pins) {
            sb.append("- ").append(pin.text())
                    .append(" [").append(pin.id()).append(", from ").append(pin.sourceNodeId()).append("]\n");
        }

        return ToolResult.ok(name(), java.util.List.of(new ToolResult.Item("pins", "", sb.toString().trim(), 1.0)));
    }
}
