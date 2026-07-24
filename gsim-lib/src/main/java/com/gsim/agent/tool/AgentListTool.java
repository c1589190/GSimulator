package com.gsim.agent.tool;

import com.gsim.agent.AgentInstance;
import com.gsim.agent.AgentStatus;
import com.gsim.agent.management.AgentsManager;
import com.gsim.llm.ToolDef;
import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * gsim_agent_list — 列出所有 Agent 实例及其状态。
 *
 * <p>支持按 configId、status、parentInstanceId 过滤。
 */
public class AgentListTool implements AgentTool {

    public static final String NAME = "agent_list";

    private final AgentsManager agentsManager;

    public AgentListTool(AgentsManager agentsManager) {
        this.agentsManager = agentsManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                列出所有 Agent 实例及其状态。
                支持可选的过滤参数：configId、status、parentInstanceId。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "configId",
                                Map.of(
                                        "type", "string",
                                        "description", "按配置 ID 过滤（可选）"),
                        "status",
                                Map.of(
                                        "type", "string",
                                        "description", "按状态过滤（可选）：PENDING, RUNNING, DONE, FAILED, CANCELLED",
                                        "enum", List.of("PENDING", "RUNNING", "DONE", "FAILED", "CANCELLED")),
                        "parentInstanceId",
                                Map.of(
                                        "type", "string",
                                        "description", "按父实例 ID 过滤（可选）")),
                List.of());
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String configId = call.param("configId", "").trim();
        String statusStr = call.param("status", "").trim();
        String parentId = call.param("parentInstanceId", "").trim();

        AgentStatus status = null;
        if (!statusStr.isEmpty()) {
            try {
                status = AgentStatus.valueOf(statusStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(
                        NAME,
                        "Invalid status: '" + statusStr + "'. Valid values: PENDING, RUNNING, DONE, FAILED, CANCELLED");
            }
        }

        List<AgentInstance> agents = agentsManager.listAgents(
                !configId.isEmpty() ? configId : null, status, !parentId.isEmpty() ? parentId : null);

        if (agents.isEmpty()) {
            return ToolResult.ok(NAME, List.of(new ToolResult.Item("empty", NAME, "当前没有匹配的 Agent 实例。", 1.0)));
        }

        StringBuilder sb = new StringBuilder("## Agent 实例列表\n\n");
        sb.append("| # | instanceId | configId | 状态 | 创建时间 | 父实例 |\n");
        sb.append("|---|------------|----------|------|----------|--------|\n");
        int idx = 1;
        for (AgentInstance a : agents) {
            String created = formatInstant(a.createdAt());
            String parent = a.parentInstanceId() != null ? a.parentInstanceId() : "-";
            sb.append(String.format(
                    "| %d | `%s` | %s | %s | %s | %s |\n",
                    idx++, a.instanceId(), a.configId(), a.status(), created, parent));
        }
        sb.append("\n共 ").append(agents.size()).append(" 个实例。");

        return ToolResult.ok(NAME, List.of(new ToolResult.Item("agent_list", NAME, sb.toString(), 1.0)));
    }

    private static String formatInstant(Instant instant) {
        if (instant == null) return "-";
        String s = instant.toString();
        return s.length() > 19 ? s.substring(0, 19) : s;
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
