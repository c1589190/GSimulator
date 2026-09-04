package com.gsim.agentsmanager.tool;

import com.gsim.agentsmanager.AgentConfigStore;
import com.gsim.agentsmanager.AgentInstance;
import com.gsim.agentsmanager.EventBusAgentProgressSink;
import com.gsim.agentsmanager.core.AgentFactory;
import com.gsim.agentsmanager.core.AgentResult;
import com.gsim.agentsmanager.event.AgentProgressSink;
import com.gsim.agentsmanager.llm.ToolDef;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.core.llm.LlmManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * dispatch_sub_agent 工具 — 异步派发子代理并立即返回。
 *
 * <p>参数:
 * <ul>
 *   <li>type (必填): 子代理类型（如 sim/search，或通过 create_sub_agent_config 创建的自定义 agent）</li>
 *   <li>prompt (必填): 子代理的任务指令</li>
 *   <li>cacheId (可选): 要加载的已有 cache sessionId，用于续接上下文</li>
 * </ul>
 *
 * <p>派发后立即返回（不阻塞主 Agent），子代理在后台虚拟线程中运行。
 * 结果通过 {@code collect_sub_agent_results}（已完成结果列表）或
 * {@code view_sub_agent_cache}（按 cacheId 查看状态与最近交互）获取。
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
    private final com.gsim.docslib.doc.DocCacheManager docCacheManager;
    private volatile com.gsim.agentsmanager.management.AgentsManager agentsManager;

    public DispatchSubAgentTool(
            LlmManager llmManager,
            ToolRegistry toolRegistry,
            String model,
            AgentProgressSink progressSink,
            Map<String, CompletableFuture<AgentResult>> runningSubAgents,
            AtomicInteger subAgentCounter,
            AgentFactory agentFactory,
            AgentConfigStore configStore,
            com.gsim.docslib.doc.DocCacheManager docCacheManager) {
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

    /**
     * 注入 AgentsManager，启用通过 HTTP API 兼容路径管理 Agent 生命周期。
     * 注入后 {@link #execute(ToolCall)} 将优先使用 AgentsManager 而非 AgentFactory 旧路径。
     *
     * @param am AgentsManager 实例，负责创建、运行和等待 Agent 完成
     */
    public void setAgentsManager(com.gsim.agentsmanager.management.AgentsManager am) {
        this.agentsManager = am;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                异步派发子代理并立即返回（不阻塞）。
                参数:
                - type: 子代理类型（当前可用的 agent 类型）
                - prompt: 子代理的任务指令
                - cacheId (可选): 要续接的已有 SubAgent cache ID
                派发后立即返回 agentId + status=RUNNING，子代理在后台运行。
                结果通过 collect_sub_agent_results（已完成列表）或 view_sub_agent_cache（按 cacheId 查看状态与最近交互）获取。
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
                        "type",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "子代理类型。当前可用: " + knownTypes.stream().collect(Collectors.joining(", ")),
                                        "enum",
                                        knownTypes),
                        "prompt",
                                Map.of(
                                        "type", "string",
                                        "description", "子代理的任务指令文本"),
                        "cacheId",
                                Map.of(
                                        "type", "string",
                                        "description",
                                                "可选 — 要加载的已有 SubAgent cache sessionId（文件名），用于续接上下文。不提供则创建空 SubAgent。")),
                List.of("type", "prompt"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String type = call.param("type", "").trim().toLowerCase(Locale.ROOT);
        String prompt = call.param("prompt", "").trim();
        String cacheId = call.param("cacheId", "").trim();
        if (cacheId.isEmpty()) cacheId = null;

        // 解析 @cache: 引用
        if (docCacheManager != null) {
            prompt = docCacheManager.resolve(prompt);
        }

        // Dynamic validation against AgentConfigStore
        // orchestrator 与子代理派发 enum（search/sim）一致地被排除——不可作为子代理类型派发
        if ("orchestrator".equals(type) || configStore.get(type) == null) {
            String available = configStore.agentIds().stream()
                    .filter(id -> !"orchestrator".equals(id))
                    .collect(Collectors.joining(", "));
            return ToolResult.fail(
                    NAME,
                    "Unknown sub-agent type: " + type
                            + ". Available: " + (available.isEmpty() ? "sim, search" : available)
                            + ". Use create_sub_agent_config to register new agent types.");
        }
        if (prompt.isEmpty()) {
            return ToolResult.fail(NAME, "prompt cannot be empty");
        }

        // 优先使用 AgentsManager（新路径 — HTTP API 兼容）
        if (agentsManager != null) {
            return executeViaAgentsManager(type, prompt, cacheId);
        }

        // Fallback: 旧路径 — AgentFactory.dispatch()
        return executeViaAgentFactory(type, prompt, cacheId);
    }

    private ToolResult executeViaAgentsManager(String type, String prompt, String cacheId) {
        AgentInstance subAgent = agentsManager.runAgent(type, cacheId, prompt, null);
        String agentId = subAgent.instanceId();
        String cacheNote = cacheId != null ? " (续接 cache: " + cacheId + ")" : "";
        log.info(
                "[DispatchSubAgent] dispatched via AgentsManager {} (type={}, promptLen={}){}, async (no wait)",
                agentId,
                type,
                prompt.length(),
                cacheNote);

        // 异步派发 — 立即返回，不阻塞等待子代理完成
        String snippet = "已派发子代理（异步，立即返回）:\n"
                + "- agentId: " + agentId + "\n"
                + "- configId: " + subAgent.configId() + "\n"
                + "- cacheId: " + subAgent.cacheId() + "\n"
                + "- status: " + subAgent.status() + "\n"
                + "子代理正在后台运行，主 Agent 不被阻塞。\n"
                + "结果通过 collect_sub_agent_results 或 view_sub_agent_cache 获取。";
        return ToolResult.ok(
                NAME, List.of(new ToolResult.Item("sub_agent_dispatched: " + agentId, NAME, snippet, 1.0)));
    }

    private ToolResult executeViaAgentFactory(String type, String prompt, String cacheId) {
        String parentTaskId = EventBusAgentProgressSink.getCurrentTaskId();
        String parentSessionId = EventBusAgentProgressSink.getCurrentSessionId();

        // 派发子代理（异步启动，不阻塞等待）
        String agentId = agentFactory.dispatch(type, prompt, parentTaskId, parentSessionId, cacheId);
        String cacheNote = cacheId != null ? " (续接 cache: " + cacheId + ")" : "";
        log.info(
                "[DispatchSubAgent] dispatched via AgentFactory {} (type={}, promptLen={}){}, async (no wait)",
                agentId,
                type,
                prompt.length(),
                cacheNote);

        // 旧路径（AgentFactory.dispatch）只返回 agentId，不返回 cacheId —
        // 可通过 list_sub_agent_caches 查询对应缓存。
        String snippet = "已派发子代理（异步，立即返回）:\n"
                + "- agentId: " + agentId + "\n"
                + "- status: RUNNING\n"
                + "- 说明: 旧路径不返回 cacheId，可通过 list_sub_agent_caches 查询缓存文件。\n"
                + "子代理正在后台运行，主 Agent 不被阻塞。\n"
                + "结果通过 collect_sub_agent_results 或 view_sub_agent_cache 获取。";
        return ToolResult.ok(
                NAME, List.of(new ToolResult.Item("sub_agent_dispatched: " + agentId, NAME, snippet, 1.0)));
    }

    @Override
    public Permission permission() {
        return Permission.SYSTEM;
    }
}
