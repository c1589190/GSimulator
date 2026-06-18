package com.gsim.context.memory;

import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

/**
 * branch_node_get — 读取节点内容（summary / messages / output / tool_logs / full）。
 */
public class BranchNodeGetTool implements AgentTool {

    private final DataManager dataManager;
    private final BranchMessageStore messageStore;

    public BranchNodeGetTool(DataManager dataManager, BranchMessageStore messageStore) {
        this.dataManager = dataManager;
        this.messageStore = messageStore;
    }

    @Override
    public String name() {
        return "branch_node_get";
    }

    @Override
    public String description() {
        return "读取指定节点内容。参数: nodeId (必填), mode (summary|messages|output|tool_logs|full)";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolResult.fail(name(), "缺少必填参数: nodeId");
        }

        String mode = call.param("mode");
        if (mode == null || mode.isBlank()) mode = "summary";

        DataDocument doc = dataManager.readById(nodeId);
        if (doc == null) {
            return ToolResult.fail(name(), "节点不存在: " + nodeId);
        }

        String content = switch (mode) {
            case "summary" -> getSummary(doc);
            case "messages" -> getMessages(nodeId);
            case "output" -> getOutput(doc);
            case "tool_logs" -> getToolLogs(nodeId);
            case "full" -> getFull(doc, nodeId);
            default -> "未知 mode: " + mode + "。可用: summary, messages, output, tool_logs, full";
        };

        return ToolResult.ok(name(), java.util.List.of(new ToolResult.Item(nodeId, nodeId, content, 1.0)));
    }

    private String getSummary(DataDocument doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(doc.id()).append(" ===\n");
        sb.append("name: ").append(doc.frontMatter().getOrDefault("name", "")).append("\n");
        sb.append("parent: ").append(doc.frontMatter().getOrDefault("parent", "")).append("\n");
        sb.append("turn: ").append(doc.frontMatter().getOrDefault("turn", "")).append("\n");
        sb.append("status: ").append(doc.frontMatter().getOrDefault("status", "")).append("\n");
        return sb.toString();
    }

    private String getMessages(String nodeId) {
        try {
            var msgs = messageStore.listMessages(nodeId);
            StringBuilder sb = new StringBuilder();
            sb.append("=== Messages for ").append(nodeId).append(" (").append(msgs.size()).append(") ===\n");
            for (BranchMessage m : msgs) {
                sb.append("[").append(m.role()).append("/").append(m.type()).append("] ");
                String c = m.content();
                if (c.length() > 500) c = c.substring(0, 497) + "...";
                sb.append(c).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "读取消息失败: " + e.getMessage();
        }
    }

    private String getOutput(DataDocument doc) {
        String simResult = extractSection(doc.body(), "三、推演结果");
        if (simResult != null && !simResult.isBlank()) {
            if (simResult.length() > 2000) simResult = simResult.substring(0, 1997) + "...\n[已截断，fullRef: " + doc.id() + "/output]";
            return "=== 推演结果 ===\n" + simResult;
        }
        return "（无推演结果）";
    }

    private String getToolLogs(String nodeId) {
        try {
            var msgs = messageStore.listMessages(nodeId);
            StringBuilder sb = new StringBuilder();
            sb.append("=== Tool Logs ===\n");
            for (BranchMessage m : msgs) {
                if ("tool_call".equals(m.type()) || "tool_result".equals(m.type())) {
                    sb.append("[").append(m.type()).append("] ").append(m.toolName()).append("\n");
                    String c = m.content();
                    if (c.length() > 1000) c = c.substring(0, 997) + "...";
                    sb.append(c).append("\n\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "读取工具日志失败: " + e.getMessage();
        }
    }

    private String getFull(DataDocument doc, String nodeId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Full Node: ").append(doc.id()).append(" ===\n");
        sb.append("source: ").append(doc.rawPath()).append("\n\n");
        String body = doc.body();
        if (body.length() > 4000) body = body.substring(0, 3997) + "...\n[已截断，fullRef: " + nodeId + "/full]";
        sb.append(body);
        return sb.toString();
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
