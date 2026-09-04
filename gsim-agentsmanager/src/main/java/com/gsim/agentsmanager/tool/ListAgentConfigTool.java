package com.gsim.agentsmanager.tool;

import com.gsim.agentsmanager.AgentConfig;
import com.gsim.agentsmanager.AgentConfigStore;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import java.util.List;
import java.util.Map;

/**
 * gsim_agent_config_list — 列出所有已保存的 Agent 配置。
 *
 * <p>返回所有 AgentConfig 的摘要信息，包括 agentId、llmProvider、工具过滤模式等。
 */
public class ListAgentConfigTool implements AgentTool {

    public static final String NAME = "agent_config_list";

    private final AgentConfigStore agentConfigStore;

    public ListAgentConfigTool(AgentConfigStore agentConfigStore) {
        this.agentConfigStore = agentConfigStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                列出所有已保存的 Agent 配置。
                返回每个配置的 configId、llmProvider、maxToolRounds、temperature、maxTokens 等摘要信息。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of());
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Map<String, AgentConfig> all = agentConfigStore.all();

        if (all.isEmpty()) {
            return ToolResult.ok(NAME, List.of(new ToolResult.Item("empty", NAME, "没有已保存的 Agent 配置。", 1.0)));
        }

        StringBuilder sb = new StringBuilder("## Agent 配置列表\n\n");
        sb.append("| # | configId | llmProvider | 工具模式 | maxRounds | temperature | maxTokens | queryScope |\n");
        sb.append("|---|----------|-------------|----------|-----------|-------------|-----------|------------|\n");
        int idx = 1;
        for (AgentConfig cfg : all.values()) {
            String toolMode = cfg.toolFilter() != null ? cfg.toolFilter().mode() : "all";
            String scopeDesc = cfg.queryScope() != null ? cfg.queryScope().toSafeString() : "未配置";
            sb.append(String.format(
                    "| %d | `%s` | %s | %s | %d | %.1f | %d | %s |\n",
                    idx++,
                    cfg.agentId(),
                    cfg.llmProvider() != null ? cfg.llmProvider() : "base",
                    toolMode,
                    cfg.maxToolRounds(),
                    cfg.temperature(),
                    cfg.maxTokens(),
                    scopeDesc));
        }

        return ToolResult.ok(NAME, List.of(new ToolResult.Item("config_list", NAME, sb.toString(), 1.0)));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
