package com.gsim.agent.tool;

import com.gsim.agent.management.AgentsManager;
import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.llm.ToolDef;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * stop_sub_agent 工具 — 按 agentId 停止一个正在运行的子代理。
 *
 * <p>通过 {@link AgentsManager#cancelAgent(String)} 取消目标子代理：
 * 设置其 cancelRequested 标志并终止运行，实例状态变为 CANCELLED。
 * 当前生产环境一律注入 AgentsManager；未注入时返回失败。
 */
public class StopSubAgentTool implements AgentTool {

    public static final String NAME = "stop_sub_agent";

    private static final Logger log = LoggerFactory.getLogger(StopSubAgentTool.class);

    private volatile AgentsManager agentsManager;

    /**
     * 注入 AgentsManager（生产路径，由 GSimulatorApplication 在注册后调用）。
     * 注入前调用 {@link #execute(ToolCall)} 返回失败。
     *
     * @param am AgentsManager 实例
     */
    public void setAgentsManager(AgentsManager am) {
        this.agentsManager = am;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                停止一个正在运行的子代理（按 agentId 取消，等效于对目标子代理发送取消信号）。
                参数:
                - agentId (必填): 要停止的子代理 instanceId，如 sim-3，从 dispatch_sub_agent 获取
                成功停止后该子代理状态变为 CANCELLED，其运行中的 ToolLoop 立即终止。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "agentId",
                        Map.of("type", "string", "description", "要停止的子代理 instanceId，如 sim-3，从 dispatch_sub_agent 获取")),
                List.of("agentId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String agentId = call.param("agentId", "").trim();
        if (agentId.isEmpty()) {
            return ToolResult.fail(NAME, "agentId 不能为空");
        }

        AgentsManager am = agentsManager;
        if (am == null) {
            return ToolResult.fail(NAME, "AgentsManager 未注入");
        }

        boolean cancelled = am.cancelAgent(agentId);
        if (cancelled) {
            log.info("[StopSubAgent] stopped {}", agentId);
            String snippet = "子代理 " + agentId + " 已停止，状态变为 CANCELLED，其 ToolLoop 已终止。";
            return ToolResult.ok(NAME, List.of(new ToolResult.Item("stopped: " + agentId, NAME, snippet, 1.0)));
        }

        return ToolResult.fail(NAME, "无法停止子代理 " + agentId + "：agent 不存在或不在运行中（可能已完成/失败/已取消）");
    }

    @Override
    public Permission permission() {
        return Permission.SYSTEM;
    }

    @Override
    public boolean mcpExposed() {
        return true;
    }
}
