package com.gsim.agent.tool;

import com.gsim.agent.AgentProgressSink;
import com.gsim.agent.config.AgentConfigStore;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * dispatch_sub_agent 工具 — 创建子代理并阻塞等待结果。
 *
 * <p>参数:
 * <ul>
 *   <li>type (必填): 子代理类型（如 sim/search，或通过 create_sub_agent_config 创建的自定义 agent）</li>
 *   <li>prompt (必填): 子代理的任务指令</li>
 *   <li>cacheId (可选): 要加载的已有 cache sessionId，用于续接上下文</li>
 * </ul>
 *
 * <p>派发后主 Agent 阻塞等待子代理完成（超时 120s），子代理的最终输出直接作为
 * 工具反馈返回。ESC 取消会传播到子代理，阻塞立即解除。
 * 不再需要单独调用 collect_sub_agent_results。
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
    private final AgentConfigStore configStore;
    private final com.gsim.doc.DocCacheManager docCacheManager;

    public DispatchSubAgentTool(LlmManager llmManager, ToolRegistry toolRegistry,
                                String model, AgentProgressSink progressSink,
                                Map<String, CompletableFuture<AgentResult>> runningSubAgents,
                                AtomicInteger subAgentCounter,
                                AgentFactory agentFactory,
                                AgentConfigStore configStore,
                                com.gsim.doc.DocCacheManager docCacheManager) {
        this.llmManager = llmManager;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.progressSink = progressSink;
        this.runningSubAgents = runningSubAgents;
        this.subAgentCounter = subAgentCounter;
        this.agentFactory = agentFactory;
        this.configStore = configStore;
        this.docCacheManager = docCacheManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                创建子代理并阻塞等待其执行完成。
                参数:
                - type: 子代理类型（当前可用的 agent 类型）
                - prompt: 子代理的任务指令
                - cacheId (可选): 要续接的已有 SubAgent cache ID
                派发后主 Agent 会阻塞等待子代理完成（超时 120s），子代理的最终输出直接返回。
                ESC 取消会传播到子代理。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        // Dynamic enum: all known agent types except the orchestrator itself
        List<String> knownTypes = new ArrayList<>(configStore.agentIds());
        knownTypes.remove("orchestrator");
        if (knownTypes.isEmpty()) {
            knownTypes.addAll(List.of("sim", "search"));
        }

        return ToolDef.strictSchema(
                Map.of(
                        "type", Map.of(
                                "type", "string",
                                "description", "子代理类型。当前可用: "
                                        + knownTypes.stream().collect(Collectors.joining(", ")),
                                "enum", knownTypes
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

        // 解析 @cache: 引用
        if (docCacheManager != null) {
            prompt = docCacheManager.resolve(prompt);
        }

        // Dynamic validation against AgentConfigStore
        if (configStore.get(type) == null) {
            String available = configStore.agentIds().stream()
                    .filter(id -> !"orchestrator".equals(id))
                    .collect(Collectors.joining(", "));
            return ToolResult.fail(NAME, "Unknown sub-agent type: " + type
                    + ". Available: " + (available.isEmpty() ? "sim, search" : available)
                    + ". Use create_sub_agent_config to register new agent types.");
        }
        if (prompt.isEmpty()) {
            return ToolResult.fail(NAME, "prompt cannot be empty");
        }

        String parentTaskId = com.gsim.agent.EventBusAgentProgressSink.getCurrentTaskId();
        String parentSessionId = com.gsim.agent.EventBusAgentProgressSink.getCurrentSessionId();

        // 派发子代理（异步启动）
        String agentId = agentFactory.dispatch(type, prompt, parentTaskId, parentSessionId, cacheId);
        String cacheNote = cacheId != null ? " (续接 cache: " + cacheId + ")" : "";
        log.info("[DispatchSubAgent] dispatched {} (type={}, promptLen={}){}, blocking...",
                agentId, type, prompt.length(), cacheNote);

        // 阻塞等待子代理完成
        CompletableFuture<AgentResult> future = agentFactory.running().get(agentId);
        if (future == null) {
            return ToolResult.fail(NAME, "子代理 " + agentId + " 已丢失（future 不存在）");
        }

        try {
            AgentResult result = future.get(120, TimeUnit.SECONDS);

            if (result.success()) {
                String text = result.finalText();
                log.info("[DispatchSubAgent] {} completed: {} chars",
                        agentId, text != null ? text.length() : 0);
                return ToolResult.ok(NAME, List.of(new ToolResult.Item(
                        "sub_agent_result: " + agentId,
                        NAME,
                        text != null ? text : "(空结果)",
                        1.0)));
            } else {
                log.warn("[DispatchSubAgent] {} failed: {}", agentId, result.error());
                return ToolResult.fail(NAME, "子代理 " + agentId + " 执行失败: " + result.error());
            }
        } catch (TimeoutException e) {
            log.warn("[DispatchSubAgent] {} timed out after 120s, cancelling", agentId);
            future.cancel(true);
            return ToolResult.fail(NAME, "子代理 " + agentId + " 执行超时（120s），已取消。");
        } catch (Exception e) {
            log.warn("[DispatchSubAgent] {} interrupted: {}", agentId, e.getMessage());
            return ToolResult.fail(NAME, "子代理 " + agentId + " 被中断: " + e.getMessage());
        }
    }
}
