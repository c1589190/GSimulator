package com.gsim.agent.tool;

import com.gsim.agent.AgentInstance;
import com.gsim.agent.management.AgentsManager;
import com.gsim.llm.ToolDef;
import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * gsim_agent_get — 查询指定 Agent 实例的详细信息。
 *
 * <p>返回 Agent 实例的状态、配置、缓存、创建时间等完整元数据。
 */
public class AgentGetTool implements AgentTool {

    public static final String NAME = "gsim_agent_get";

    private final AgentsManager agentsManager;

    public AgentGetTool(AgentsManager agentsManager) {
        this.agentsManager = agentsManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                查询指定 Agent 实例的详细信息和当前状态。
                参数:
                - instanceId (必填): Agent 实例 ID。
                返回 instanceId、configId、sessionId、taskId、cacheId、status、parentInstanceId、
                prompt、createdAt、finishedAt、error 等完整信息。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "instanceId",
                        Map.of(
                                "type", "string",
                                "description", "Agent 实例 ID")),
                List.of("instanceId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String instanceId = call.param("instanceId", "").trim();

        if (instanceId.isEmpty()) {
            return ToolResult.fail(NAME, "instanceId 不能为空");
        }

        AgentInstance agent = agentsManager.getAgent(instanceId);
        if (agent == null) {
            return ToolResult.fail(NAME, "Agent 实例不存在: " + instanceId);
        }

        StringBuilder sb =
                new StringBuilder("## Agent 实例: `").append(agent.instanceId()).append("`\n\n");
        sb.append("- **configId**: ").append(agent.configId()).append("\n");
        sb.append("- **sessionId**: ").append(agent.sessionId()).append("\n");
        sb.append("- **taskId**: ").append(agent.taskId()).append("\n");
        sb.append("- **cacheId**: ").append(agent.cacheId()).append("\n");
        sb.append("- **状态**: ").append(agent.status()).append("\n");
        sb.append("- **创建时间**: ")
                .append(agent.createdAt() != null ? agent.createdAt() : "-")
                .append("\n");
        sb.append("- **完成时间**: ")
                .append(agent.finishedAt() != null ? agent.finishedAt() : "-")
                .append("\n");
        sb.append("- **父实例**: ")
                .append(agent.parentInstanceId() != null ? agent.parentInstanceId() : "-")
                .append("\n");
        sb.append("- **prompt**: ")
                .append(agent.prompt() != null ? agent.prompt() : "")
                .append("\n");
        if (agent.error() != null) {
            sb.append("- **错误**: ").append(agent.error()).append("\n");
        }

        return ToolResult.ok(NAME, List.of(new ToolResult.Item("agent:" + instanceId, NAME, sb.toString(), 1.0)));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
