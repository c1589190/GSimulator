package com.gsim.agent.tool;

import com.gsim.agent.management.AgentsManager;
import com.gsim.llm.ToolDef;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * gsim_agent_cancel — 取消一个正在运行的 Agent 实例。
 *
 * <p>取消信号会传播到 Agent 的虚拟线程及其 Future。
 */
public class AgentCancelTool implements AgentTool {

    public static final String NAME = "gsim_agent_cancel";

    private final AgentsManager agentsManager;

    public AgentCancelTool(AgentsManager agentsManager) {
        this.agentsManager = agentsManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                取消一个正在运行的 Agent 实例。
                参数:
                - instanceId (必填): 要取消的 Agent 实例 ID。
                取消信号会传播到 Agent 执行线程，状态变更为 CANCELLED。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "instanceId",
                        Map.of(
                                "type", "string",
                                "description", "要取消的 Agent 实例 ID")),
                List.of("instanceId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String instanceId = call.param("instanceId", "").trim();

        if (instanceId.isEmpty()) {
            return ToolResult.fail(NAME, "instanceId 不能为空");
        }

        boolean ok = agentsManager.cancelAgent(instanceId);
        if (ok) {
            return ToolResult.ok(
                    NAME,
                    List.of(new ToolResult.Item(
                            "cancelled:" + instanceId, NAME, "Agent `" + instanceId + "` 已取消。", 1.0)));
        }

        return ToolResult.fail(NAME, "Agent 实例不存在或已不在运行中: " + instanceId);
    }
}
