package com.gsim.context.memory;

import com.gsim.context.summary.BranchPathSummaryRenderer;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

/**
 * branch_path — 返回当前分支概要链。
 */
public class BranchPathTool implements AgentTool {

    private final BranchPathSummaryRenderer pathRenderer;

    public BranchPathTool(BranchPathSummaryRenderer pathRenderer) {
        this.pathRenderer = pathRenderer;
    }

    @Override
    public String name() {
        return "branch_path";
    }

    @Override
    public String description() {
        return "查看当前分支节点概要链。参数: limit (可选，默认 20)";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String markdown = pathRenderer.renderActivePath();
        return ToolResult.ok(name(), java.util.List.of(new ToolResult.Item("Branch Path", "", markdown, 1.0)));
    }
}
