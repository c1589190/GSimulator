package com.gsim.agent.tool;

import com.gsim.agent.AgentProgressSink;
import com.gsim.agent.core.AgentConfig;
import com.gsim.agent.core.AgentFactory;
import com.gsim.agent.core.AgentResult;
import com.gsim.llm.LlmManager;
import com.gsim.llm.ToolDef;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * dispatch_sub_agent 工具 — 创建并启动子代理。
 *
 * <p>参数:
 * <ul>
 *   <li>type (必填): "sim" 或 "search"</li>
 *   <li>prompt (必填): 子代理的任务指令</li>
 *   <li>cacheId (可选): 要加载的已有 cache sessionId，用于续接之前的 SubAgent 上下文</li>
 * </ul>
 *
 * <p>子代理在 Virtual Thread 中异步执行，结果通过 CompletableFuture 收集。
 * 调用 collect_sub_agent_results 等待所有子代理完成并聚合结果。
 * 每次派发都会返回 cache sessionId，可在后续 dispatch 中传入 cacheId 以续接上下文。
 */
public class DispatchSubAgentTool implements AgentTool {

    public static final String NAME = "dispatch_sub_agent";

    private static final Logger log = LoggerFactory.getLogger(DispatchSubAgentTool.class);

    private final LlmManager llmManager;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final AgentProgressSink progressSink;
    private final Map<String, CompletableFuture<AgentResult>> runningSubAgents;
    private final AtomicInteger subAgentCounter;
    private final AgentFactory agentFactory;

    public DispatchSubAgentTool(LlmManager llmManager, ToolRegistry toolRegistry,
                                String model, AgentProgressSink progressSink,
                                Map<String, CompletableFuture<AgentResult>> runningSubAgents,
                                AtomicInteger subAgentCounter,
                                AgentFactory agentFactory) {
        this.llmManager = llmManager;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.progressSink = progressSink;
        this.runningSubAgents = runningSubAgents;
        this.subAgentCounter = subAgentCounter;
        this.agentFactory = agentFactory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                创建并启动一个子代理（SimAgent 或 SearchAgent）。
                参数:
                - type: "sim"（推演叙事生成）或 "search"（深度资料搜索）
                - prompt: 子代理的任务指令
                - cacheId (可选): 要续接的已有 SubAgent cache ID
                子代理在后台异步执行。使用 collect_sub_agent_results 收集结果。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "type", Map.of(
                                "type", "string",
                                "description", "子代理类型：sim（推演叙事）或 search（资料搜索）",
                                "enum", List.of("sim", "search")
                        ),
                        "prompt", Map.of(
                                "type", "string",
                                "description", "子代理的任务指令文本"
                        ),
                        "cacheId", Map.of(
                                "type", "string",
                                "description", "可选 — 要加载的已有 SubAgent cache sessionId（文件名），用于续接上下文。不提供则创建空 SubAgent。"
                        )
                ),
                List.of("type", "prompt")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String type = call.param("type", "").trim().toLowerCase();
        String prompt = call.param("prompt", "").trim();
        String cacheId = call.param("cacheId", "").trim();
        if (cacheId.isEmpty()) cacheId = null;

        if (!Set.of("sim", "search").contains(type)) {
            return ToolResult.fail(NAME, "Unknown sub-agent type: " + type
                    + ". Allowed: sim, search");
        }
        if (prompt.isEmpty()) {
            return ToolResult.fail(NAME, "prompt cannot be empty");
        }

        String parentTaskId = com.gsim.agent.EventBusAgentProgressSink.getCurrentTaskId();
        String parentSessionId = com.gsim.agent.EventBusAgentProgressSink.getCurrentSessionId();

        // 使用 AgentFactory 创建并启动子代理（支持 cache 续接）
        String agentId = agentFactory.dispatch(type, prompt, parentTaskId, parentSessionId, cacheId);

        // 从 AgentFactory 取出 future 放入 OrchestratorAgent 的 runningSubAgents map
        java.util.concurrent.CompletableFuture<AgentResult> future = agentFactory.running().get(agentId);
        if (future != null) {
            runningSubAgents.put(agentId, future);
        }

        String cacheNote = cacheId != null ? " (续接 cache: " + cacheId + ")" : "";
        log.info("[DispatchSubAgent] created {} (type={}, promptLen={}){}",
                agentId, type, prompt.length(), cacheNote);

        return ToolResult.ok(NAME, List.of(new ToolResult.Item(
                "dispatched: " + agentId,
                NAME,
                "子代理 " + agentId + "（类型: " + type + "）已启动" + cacheNote + "。"
                        + "当前运行中的子代理: " + runningSubAgents.keySet() + "。"
                        + "请调用 collect_sub_agent_results 等待并收集所有子代理结果。",
                1.0)));
    }
}
