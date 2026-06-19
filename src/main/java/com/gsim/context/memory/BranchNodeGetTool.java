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
        return "读取指定节点内容。参数: nodeId (必填), mode (summary|messages|output|tool_logs|full)。"
                + "mode=full 支持分页: offset(可选,默认0), limit(可选,默认8000), full(可选,默认false)。"
                + "full=true 返回全文(上限30000)。返回 truncated/originalLength/returnedRange。"
                + "message 和 tool_logs 各自截断 500/1000 chars。";
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
            case "full" -> getFullPaginated(doc, nodeId, call);
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

    private static final int DEFAULT_LIMIT = 8000;
    private static final int MAX_FULL_CHARS = 30000;

    private String getFullPaginated(DataDocument doc, String nodeId, ToolCall call) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Full Node: ").append(doc.id()).append(" ===\n");
        sb.append("source: ").append(doc.rawPath()).append("\n\n");

        String body = doc.body();
        if (body == null) body = "";
        int originalLength = body.length();
        boolean full = "true".equalsIgnoreCase(call.param("full", "false"));
        int offset = parseIntParam(call.param("offset"), 0, 0);
        int limit = parseIntParam(call.param("limit"), DEFAULT_LIMIT, 1);

        int start, end;
        boolean truncated;

        if (full) {
            start = 0;
            end = Math.min(originalLength, MAX_FULL_CHARS);
            truncated = originalLength > MAX_FULL_CHARS;
        } else {
            start = Math.min(offset, originalLength);
            end = Math.min(start + limit, originalLength);
            truncated = end < originalLength;
        }

        sb.append("originalLength: ").append(originalLength).append("\n");
        sb.append("truncated: ").append(truncated).append("\n");
        sb.append("returnedRange: ").append(start).append("-").append(end).append("\n");
        sb.append("---\n");

        String content = body.substring(start, end);
        if (truncated) {
            content += "\n\n[已截断，使用 offset=" + end + " 继续读取]";
        }
        sb.append(content);
        return sb.toString();
    }

    private static int parseIntParam(String value, int defaultValue, int minValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            int v = Integer.parseInt(value.trim());
            return Math.max(minValue, v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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
