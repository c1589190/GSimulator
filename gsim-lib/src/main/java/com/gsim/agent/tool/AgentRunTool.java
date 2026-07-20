package com.gsim.agent.tool;

import com.gsim.agent.AgentInstance;
import com.gsim.agent.management.AgentsManager;
import com.gsim.llm.ToolDef;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * gsim_agent_run — 启动一个新的 Agent 实例。
 *
 * <p>异步启动 Agent，立即返回 instanceId 用于后续跟踪。
 * 默认使用 'orchestrator' 配置，可通过 configId 参数指定其他配置。
 */
public class AgentRunTool implements AgentTool {

    public static final String NAME = "gsim_agent_run";

    private final AgentsManager agentsManager;

    public AgentRunTool(AgentsManager agentsManager) {
        this.agentsManager = agentsManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                启动一个新的 Agent 实例。异步执行，立即返回 instanceId 用于后续跟踪。
                参数:
                - prompt (必填): Agent 任务指令/提示词
                - configId (可选): Agent 配置 ID，默认 'orchestrator'
                - cacheId (可选): 续接上下文的缓存 ID
                - parentInstanceId (可选): 父 Agent 实例 ID（用于 SubAgent 链）
                - sessionId (可选): 会话 ID（自动生成）
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "prompt",
                                Map.of(
                                        "type", "string",
                                        "description", "Agent 任务指令/提示词"),
                        "configId",
                                Map.of(
                                        "type", "string",
                                        "description", "Agent 配置 ID（默认: 'orchestrator'）"),
                        "cacheId",
                                Map.of(
                                        "type", "string",
                                        "description", "续接上下文的缓存 ID（可选）"),
                        "parentInstanceId",
                                Map.of(
                                        "type", "string",
                                        "description", "父 Agent 实例 ID，用于 SubAgent 链（可选）"),
                        "sessionId",
                                Map.of(
                                        "type", "string",
                                        "description", "会话 ID（可选，自动生成）")),
                List.of("prompt"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String prompt = call.param("prompt", "").trim();
        if (prompt.isEmpty()) {
            return ToolResult.fail(NAME, "prompt 不能为空");
        }

        String configId = call.param("configId", "").trim();
        if (configId.isEmpty()) {
            configId = "orchestrator";
        }

        String cacheId = call.param("cacheId", "").trim();
        String parentInstanceId = call.param("parentInstanceId", "").trim();

        AgentInstance instance = agentsManager.runAgent(
                configId,
                !cacheId.isEmpty() ? cacheId : null,
                prompt,
                !parentInstanceId.isEmpty() ? parentInstanceId : null);

        StringBuilder sb = new StringBuilder("## Agent 已启动\n\n");
        sb.append("- **instanceId**: `").append(instance.instanceId()).append("`\n");
        sb.append("- **configId**: ").append(instance.configId()).append("\n");
        sb.append("- **cacheId**: ").append(instance.cacheId()).append("\n");
        sb.append("- **sessionId**: ").append(instance.sessionId()).append("\n");
        sb.append("- **taskId**: ").append(instance.taskId()).append("\n");
        sb.append("- **状态**: ").append(instance.status()).append("\n");
        if (instance.parentInstanceId() != null) {
            sb.append("- **父实例**: ").append(instance.parentInstanceId()).append("\n");
        }
        sb.append("\n使用 `gsim_agent_get` 传入 instanceId 查看状态，或 `gsim_agent_output` 获取最终输出。");

        return ToolResult.ok(
                NAME, List.of(new ToolResult.Item("agent:" + instance.instanceId(), NAME, sb.toString(), 1.0)));
    }
}
