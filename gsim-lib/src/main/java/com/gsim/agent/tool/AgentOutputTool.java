package com.gsim.agent.tool;

import com.gsim.agent.management.AgentsManager;
import com.gsim.llm.ToolDef;
import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * gsim_agent_output — 获取已完成 Agent 实例的最终输出文本。
 *
 * <p>从 Agent 的对话缓存中提取最后一条 assistant 角色的消息内容。
 * 仅对状态为 DONE 或 FAILED 的 Agent 有效。
 */
public class AgentOutputTool implements AgentTool {

    public static final String NAME = "gsim_agent_output";

    private final AgentsManager agentsManager;

    public AgentOutputTool(AgentsManager agentsManager) {
        this.agentsManager = agentsManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                获取已完成 Agent 实例的最终输出文本。
                参数:
                - instanceId (必填): Agent 实例 ID。
                仅对状态为 DONE 或 FAILED 的 Agent 返回有效输出。
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

        String output = agentsManager.getAgentOutput(instanceId);
        if (output == null) {
            return ToolResult.fail(NAME, "Agent 实例不存在或尚未完成: " + instanceId + "。请使用 gsim_agent_get 检查状态。");
        }

        return ToolResult.ok(NAME, List.of(new ToolResult.Item("output:" + instanceId, NAME, output, 1.0)));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
