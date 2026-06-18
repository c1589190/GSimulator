package com.gsim.context.memory;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.List;

/**
 * branch_pin_add — 添加硬约束。
 */
public class BranchPinAddTool implements AgentTool {

    private final PinnedConstraintManager pinManager;

    public BranchPinAddTool(PinnedConstraintManager pinManager) {
        this.pinManager = pinManager;
    }

    @Override
    public String name() {
        return "branch_pin_add";
    }

    @Override
    public String description() {
        return "添加硬约束。参数: text (必填), sourceNodeId (可选)";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String text = call.param("text");
        if (text == null || text.isBlank()) {
            return ToolResult.fail(name(), "缺少必填参数: text");
        }

        String sourceNodeId = call.param("sourceNodeId");
        if (sourceNodeId == null) sourceNodeId = "agent";

        PinnedConstraint pin = pinManager.addPin("current", text, sourceNodeId, "agent");

        String msg = "硬约束已添加: " + pin.text() + " [id: " + pin.id() + ", from: " + sourceNodeId + "]";
        ToolResult.Item item = new ToolResult.Item("pin", "", msg, 1.0);

        return ToolResult.ok(name(), List.of(item));
    }
}
