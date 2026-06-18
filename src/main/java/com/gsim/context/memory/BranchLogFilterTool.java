package com.gsim.context.memory;

import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.*;

/**
 * branch_log_filter — 按字段读取节点日志。
 */
public class BranchLogFilterTool implements AgentTool {

    private static final int MAX_SECTION_LENGTH = 1500;

    private final DataManager dataManager;

    public BranchLogFilterTool(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    public String name() {
        return "branch_log_filter";
    }

    @Override
    public String description() {
        return "按字段读取节点日志。参数: nodeId (必填), fields (逗号分隔: user_input,agent_output,tool_calls,tool_results,knowledge_refs)";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolResult.fail(name(), "缺少必填参数: nodeId");
        }

        String fieldsStr = call.param("fields");
        if (fieldsStr == null || fieldsStr.isBlank()) {
            return ToolResult.fail(name(), "缺少必填参数: fields (逗号分隔)");
        }

        DataDocument doc = dataManager.readById(nodeId);
        if (doc == null) {
            return ToolResult.fail(name(), "节点不存在: " + nodeId);
        }

        String[] fields = fieldsStr.split(",");
        StringBuilder sb = new StringBuilder();
        sb.append("=== Filtered Log: ").append(nodeId).append(" (fields: ").append(fieldsStr).append(") ===\n\n");

        for (String field : fields) {
            String trimmed = field.trim();
            String content = switch (trimmed) {
                case "user_input" -> extractSection(doc.body(), "一、本节点输入");
                case "agent_output", "sim_result" -> extractSection(doc.body(), "三、推演结果");
                case "world_delta" -> extractSection(doc.body(), "四、世界观/设定增量");
                case "entity_delta" -> extractSection(doc.body(), "五、实体状态增量");
                case "rule_delta" -> extractSection(doc.body(), "六、推演规则增量");
                case "interaction_delta" -> extractSection(doc.body(), "七、交互逻辑增量");
                case "skill_delta" -> extractSection(doc.body(), "八、未总结 Skill 增量");
                case "risks" -> extractSection(doc.body(), "九、下节点风险");
                case "tool_calls", "tool_results" -> extractSection(doc.body(), "二、LLM 上下文记录");
                default -> "";
            };

            if (content == null || content.isBlank()) {
                sb.append("## ").append(trimmed).append("\n_（无内容）_\n\n");
            } else {
                sb.append("## ").append(trimmed).append("\n");
                if (content.length() > MAX_SECTION_LENGTH) {
                    content = content.substring(0, MAX_SECTION_LENGTH - 3) + "...";
                    sb.append(content).append("\n_[已截断，fullRef: ").append(nodeId)
                            .append("#").append(trimmed).append("]_\n\n");
                } else {
                    sb.append(content).append("\n\n");
                }
            }
        }

        return ToolResult.ok(name(), java.util.List.of(new ToolResult.Item(nodeId, nodeId, sb.toString().trim(), 1.0)));
    }

    private String extractSection(String body, String heading) {
        if (body == null) return "";
        String marker = "## " + heading;
        int start = body.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = body.indexOf("\n## ", start);
        if (end < 0) end = body.length();
        return body.substring(start, end).trim();
    }
}
