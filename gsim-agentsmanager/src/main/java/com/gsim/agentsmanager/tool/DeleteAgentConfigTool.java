package com.gsim.agentsmanager.tool;

import com.gsim.agentsmanager.AgentConfigStore;
import com.gsim.agentsmanager.llm.ToolDef;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * gsim_agent_config_delete — 删除一个 Agent 配置。
 *
 * <p>从 filesystem agents/ 目录中删除对应的 .json 配置文件，
 * 并从运行时缓存中移除。
 */
public class DeleteAgentConfigTool implements AgentTool {

    public static final String NAME = "agent_config_delete";

    private final AgentConfigStore agentConfigStore;
    private final Path agentsDir;

    public DeleteAgentConfigTool(AgentConfigStore agentConfigStore, Path agentsDir) {
        this.agentConfigStore = agentConfigStore;
        this.agentsDir = agentsDir;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                删除一个 Agent 配置。
                参数:
                - configId (必填): 要删除的配置 ID。
                从 filesystem agents/ 目录中删除对应的 .json 文件并从运行时缓存中移除。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "configId",
                        Map.of(
                                "type", "string",
                                "description", "要删除的配置 ID")),
                List.of("configId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String configId = call.param("configId", "").trim();

        if (configId.isEmpty()) {
            return ToolResult.fail(NAME, "configId 不能为空");
        }

        try {
            // Ensure agentsDir is initialized
            java.nio.file.Files.createDirectories(agentsDir);
            agentConfigStore.reload(agentsDir);

            boolean deleted = agentConfigStore.deleteConfig(configId);
            if (deleted) {
                return ToolResult.ok(
                        NAME,
                        List.of(new ToolResult.Item(
                                "deleted:" + configId, NAME, "Agent 配置 '" + configId + "' 已删除。", 1.0)));
            }
            return ToolResult.fail(NAME, "Agent 配置不存在: " + configId);
        } catch (IOException e) {
            return ToolResult.fail(NAME, "删除 Agent 配置失败: " + e.getMessage());
        }
    }

    @Override
    public Permission permission() {
        return Permission.SYSTEM;
    }
}
