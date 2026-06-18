package com.gsim.branch;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.List;

/**
 * branch_analysis — Agent 可调用的节点态势分析工具。
 *
 * 只读，不修改任何文件，不创建分支，不推进时间。
 *
 * 参数：
 *   branchId    — 可选，不传则分析当前 active branch
 *   detailLevel — 可选，compact（默认）或 full
 */
public class BranchAnalysisTool implements AgentTool {

    public static final String NAME = "branch_analysis";

    private final BranchAnalyzer analyzer;

    public BranchAnalysisTool(BranchAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                分析当前节点状况：节点年龄状态（新节点/已推演/已分支/讨论节点）、
                是否有可前进分支、实体/玩家/规则/世界观统计、消息历史统计、旧节点检测。
                只读，无副作用。
                参数：
                  branchId: 可选，不传则分析当前 active branch
                  detailLevel: 可选，"compact"（默认，约 20 行）或 "full"
                """;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String branchId = call.param("branchId");
        String detailLevel = call.param("detailLevel", "compact");

        try {
            BranchAnalysis analysis = analyzer.analyze(branchId, detailLevel);

            String output = "compact".equalsIgnoreCase(detailLevel)
                    ? BranchAnalyzer.renderCompactMarkdown(analysis)
                    : BranchAnalyzer.renderFullMarkdown(analysis);

            ToolResult.Item item = new ToolResult.Item(
                    "Node Analysis: " + analysis.activeBranchId(),
                    analysis.activeBranchId(),
                    output,
                    1.0);

            return ToolResult.ok(NAME, List.of(item));

        } catch (Exception e) {
            return ToolResult.fail(NAME, "分析失败: " + e.getMessage());
        }
    }
}
