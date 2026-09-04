package com.gsim.agentsmanager.management;

import com.gsim.agent.AgentConfig;
import com.gsim.agent.AgentConfigStore;
import com.gsim.agent.AgentInstance;
import com.gsim.agent.AgentStatus;
import com.gsim.agent.EventBusAgentProgressSink;
import com.gsim.agent.core.AbstractAgent;
import com.gsim.agent.core.AgentResult;
import com.gsim.agentsmanager.tool.ToolRegistry;
import com.gsim.core.cache.CacheSession;
import com.gsim.core.event.AgentProgressSink;
import com.gsim.core.event.EventBus;
import com.gsim.core.event.GSimEvent;
import com.gsim.core.llm.LlmManager;
import com.gsim.core.llm.LlmMessage;
import com.gsim.core.llm.LlmProvider;
import com.gsim.core.llm.LlmProviderRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 生命周期管理器 — 门面模式。
 *
 * <p>统一管理所有 Agent（主 Agent 和 SubAgent），通过 HTTP API 暴露。
 * 主 Agent 和 SubAgent 仅在 parentInstanceId 字段上区分。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>创建和运行 Agent（异步，立即返回 instanceId）</li>
 *   <li>管理 Agent 生命周期状态</li>
 *   <li>发布 SSE 事件到 EventBus</li>
 *   <li>提供同步等待（供工具链内部使用）</li>
 * </ul>
 */
public class AgentsManager {

    private static final Logger log = LoggerFactory.getLogger(AgentsManager.class);

    private final AgentConfigStore configStore;
    private final AgentCacheStore cacheStore;
    private final LlmProviderRegistry llmRegistry;
    private final ToolRegistry allTools;
    private final AgentProgressSink rootSink;
    private final EventBus eventBus;
    private final String model;
    private final AbstractAgent.ToolResultPolicy resultPolicy;

    private final AtomicInteger counter = new AtomicInteger(0);
    private final Map<String, AgentInstance> instances = new ConcurrentHashMap<>();
    private final Map<String, AbstractAgent> runningAgents = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<AgentResult>> futures = new ConcurrentHashMap<>();

    /**
     * 构造 Agent 生命周期管理器。
     *
     * @param configStore      Agent 配置存储
     * @param cacheStore       Agent 对话缓存存储
     * @param llmRegistry      LLM Provider 注册表
     * @param allTools         全局工具注册表
     * @param rootSink         根级 Agent 进度监听器
     * @param eventBus         全局事件总线
     * @param model            默认 LLM 模型名
     */
    public AgentsManager(
            AgentConfigStore configStore,
            AgentCacheStore cacheStore,
            LlmProviderRegistry llmRegistry,
            ToolRegistry allTools,
            AgentProgressSink rootSink,
            EventBus eventBus,
            String model,
            AbstractAgent.ToolResultPolicy resultPolicy) {
        this.configStore = configStore;
        this.cacheStore = cacheStore;
        this.llmRegistry = llmRegistry;
        this.allTools = allTools;
        this.rootSink = rootSink;
        this.eventBus = eventBus;
        this.model = model;
        this.resultPolicy = resultPolicy;
    }

    // ══════════════════════════════════════════
    // 公共 API — Agent 生命周期
    // ══════════════════════════════════════════

    /**
     * 异步启动一个 Agent，立即返回 instanceId。
     *
     * @param configId         Agent 配置 ID（如 "orchestrator", "sim"）
     * @param cacheId          对话缓存 ID，null = 自动创建新缓存
     * @param prompt           任务指令
     * @param parentInstanceId 父 Agent 实例 ID（SubAgent 非 null）
     * @return AgentInstance（status=RUNNING）
     */
    public AgentInstance runAgent(String configId, String cacheId, String prompt, String parentInstanceId) {
        int id = counter.incrementAndGet();
        String instanceId = configId + "-" + id;
        String sessionId = "agent-" + instanceId;
        String taskId = "task-" + instanceId;

        // 获取或创建缓存
        CacheSession resolvedCache = null;
        if (cacheId != null && !cacheId.isBlank()) {
            resolvedCache = cacheStore.get(cacheId);
        }
        if (resolvedCache == null) {
            if (cacheId != null && !cacheId.isBlank()) {
                log.warn("Cache not found: {}, creating new", cacheId);
            }
            resolvedCache = cacheStore.create(configId);
        }
        final CacheSession cache = resolvedCache;
        final String resolvedCacheId = cache.sessionId();

        // 创建 AgentInstance
        AgentInstance instance = new AgentInstance(
                instanceId,
                configId,
                sessionId,
                taskId,
                resolvedCacheId,
                parentInstanceId,
                prompt,
                AgentStatus.RUNNING,
                Instant.now(),
                null,
                null);
        instances.put(instanceId, instance);

        // 创建 AbstractAgent
        AgentConfig config = configStore.get(configId);
        if (config == null) {
            AgentInstance failedRef =
                    instance.withStatus(AgentStatus.FAILED, Instant.now(), "Unknown agent config: " + configId);
            instances.put(instanceId, failedRef);
            return failedRef;
        }

        LlmProvider provider = llmRegistry.get(config.llmProvider());
        AbstractAgent agent = new AbstractAgent(config, (LlmManager) provider, allTools, rootSink, model, resultPolicy);
        agent.setAgentId(instanceId);
        agent.setMessageSaver(msg -> cacheStore.appendMessage(resolvedCacheId, msg.toCacheMap()));

        // 发布 agent_started 事件
        publishEvent(
                sessionId,
                taskId,
                "agent_started",
                Map.of(
                        "instanceId", instanceId,
                        "configId", configId,
                        "parentInstanceId", parentInstanceId != null ? parentInstanceId : "",
                        "cacheId", resolvedCacheId));

        CompletableFuture<AgentResult> future = new CompletableFuture<>();
        futures.put(instanceId, future);
        runningAgents.put(instanceId, agent);

        final String finalParentId = parentInstanceId;
        Thread.startVirtualThread(() -> {
            try {
                EventBusAgentProgressSink.bindTask(sessionId, taskId);

                // 加载历史消息作为上下文
                List<LlmMessage> priorMessages = cacheMessagesToLlm(cache);

                // 发布 llm_started
                publishEvent(sessionId, taskId, "llm_started", Map.of());

                AgentResult rawResult = agent.run(prompt, priorMessages);

                AgentResult result = new AgentResult(
                        rawResult.agentId(),
                        rawResult.success(),
                        rawResult.finalText(),
                        rawResult.rounds(),
                        rawResult.totalToolCalls(),
                        rawResult.error(),
                        resolvedCacheId);

                if (result.success()) {
                    publishEvent(
                            sessionId,
                            taskId,
                            "agent_result",
                            Map.of(
                                    "instanceId",
                                    instanceId,
                                    "cacheId",
                                    resolvedCacheId,
                                    "finalText",
                                    truncate(result.finalText(), 2000),
                                    "totalToolCalls",
                                    String.valueOf(result.totalToolCalls())));
                    instances.put(instanceId, instance.withStatus(AgentStatus.DONE, Instant.now(), null));
                } else {
                    publishEvent(
                            sessionId,
                            taskId,
                            "agent_error",
                            Map.of(
                                    "instanceId",
                                    instanceId,
                                    "error",
                                    result.error() != null ? result.error() : "unknown"));
                    instances.put(instanceId, instance.withStatus(AgentStatus.FAILED, Instant.now(), result.error()));
                }

                publishEvent(
                        sessionId,
                        taskId,
                        "agent_done",
                        Map.of("instanceId", instanceId, "status", result.success() ? "DONE" : "FAILED"));

                future.complete(result);
            } catch (Exception e) {
                log.error("Agent {} failed: {}", instanceId, e.getMessage(), e);
                publishEvent(
                        sessionId, taskId, "agent_error", Map.of("instanceId", instanceId, "error", e.getMessage()));
                instances.put(instanceId, instance.withStatus(AgentStatus.FAILED, Instant.now(), e.getMessage()));
                future.complete(AgentResult.fail(instanceId, e.getMessage(), resolvedCacheId));
            } finally {
                runningAgents.remove(instanceId);
                EventBusAgentProgressSink.unbindTask();
                publishEvent(sessionId, taskId, "done", Map.of());
            }
        });

        log.info(
                "[AgentsManager] started {} (config={}, cache={}, parent={})",
                instanceId,
                configId,
                resolvedCacheId,
                parentInstanceId);
        return instance;
    }

    /**
     * 取消正在运行的 Agent。
     *
     * @param instanceId Agent 实例 ID
     * @return 是否成功取消（false 表示实例不存在或已不在运行中）
     */
    public boolean cancelAgent(String instanceId) {
        AgentInstance instance = instances.get(instanceId);
        if (instance == null) return false;

        if (instance.status() == AgentStatus.RUNNING || instance.status() == AgentStatus.PENDING) {
            AbstractAgent agent = runningAgents.get(instanceId);
            if (agent != null) {
                agent.cancel();
            }
            CompletableFuture<AgentResult> future = futures.get(instanceId);
            if (future != null) {
                future.cancel(true);
            }
            instances.put(instanceId, instance.withStatus(AgentStatus.CANCELLED, Instant.now(), null));
            publishEvent(
                    instance.sessionId(),
                    instance.taskId(),
                    "agent_done",
                    Map.of("instanceId", instanceId, "status", "CANCELLED"));
            publishEvent(instance.sessionId(), instance.taskId(), "done", Map.of());
            log.info("[AgentsManager] cancelled {}", instanceId);
            return true;
        }
        return false;
    }

    /**
     * 查询 Agent 实例状态。
     *
     * @param instanceId Agent 实例 ID
     * @return AgentInstance 对象，不存在时返回 null
     */
    public AgentInstance getAgent(String instanceId) {
        return instances.get(instanceId);
    }

    /**
     * 列出 Agent 实例，支持可选的过滤条件。
     *
     * @param configId         按配置 ID 过滤，null 表示不过滤
     * @param status           按状态过滤，null 表示不过滤
     * @param parentInstanceId 按父实例 ID 过滤，null 表示不过滤
     * @return 符合条件的 AgentInstance 列表
     */
    public List<AgentInstance> listAgents(String configId, AgentStatus status, String parentInstanceId) {
        return instances.values().stream()
                .filter(a -> configId == null || configId.equals(a.configId()))
                .filter(a -> status == null || status == a.status())
                .filter(a -> parentInstanceId == null || parentInstanceId.equals(a.parentInstanceId()))
                .toList();
    }

    /**
     * 按 cacheId 查找对应的 Agent 实例（返回最新创建的一个）。
     *
     * <p>供 view_sub_agent_cache 等工具将缓存文件关联到运行时实例，
     * 以区分"正在运行 / 已完成 / 历史缓存"。
     *
     * @param cacheId 对话缓存 ID
     * @return 匹配的 AgentInstance，未找到时返回 null
     */
    public AgentInstance getByCacheId(String cacheId) {
        if (cacheId == null || cacheId.isBlank()) return null;
        AgentInstance latest = null;
        for (AgentInstance a : instances.values()) {
            if (!cacheId.equals(a.cacheId())) continue;
            if (latest == null) {
                latest = a;
            } else if (a.createdAt() != null
                    && (latest.createdAt() == null || a.createdAt().isAfter(latest.createdAt()))) {
                latest = a;
            }
        }
        return latest;
    }

    /**
     * 列出所有已完成的 Agent 实例（status==DONE），支持可选过滤。
     *
     * <p>供 collect_sub_agent_results 工具聚合已完成子代理结果（非阻塞）。
     *
     * @param configId  按配置 ID 过滤，null 表示不过滤
     * @param agentType 按 Agent 类型（configId）过滤，null 表示不过滤
     * @return 已完成（DONE）的 AgentInstance 列表
     */
    public List<AgentInstance> listDoneAgents(String configId, String agentType) {
        return instances.values().stream()
                .filter(a -> a.status() == AgentStatus.DONE)
                .filter(a -> configId == null || configId.equals(a.configId()))
                .filter(a -> agentType == null || agentType.equals(a.configId()))
                .toList();
    }

    /**
     * 获取 Agent 的最终执行结果（仅已完成时可用）。
     *
     * <p>从内存 future 读取 {@link AgentResult}（含 finalText、rounds、totalToolCalls），
     * 未完成或实例不存在时返回 null。与 {@link #getAgentOutput} 互补：
     * 前者返回结构化结果，后者从缓存提取最后 assistant 消息文本。
     *
     * @param instanceId Agent 实例 ID
     * @return 已完成的 AgentResult，未完成/不存在时返回 null
     */
    public AgentResult getCompletedResult(String instanceId) {
        AgentInstance instance = instances.get(instanceId);
        if (instance == null) return null;
        if (instance.status() != AgentStatus.DONE && instance.status() != AgentStatus.FAILED) return null;
        CompletableFuture<AgentResult> future = futures.get(instanceId);
        if (future == null) return null;
        return future.getNow(null);
    }

    /**
     * 获取已完成 Agent 的最终输出文本。
     *
     * <p>从缓存中提取最后一条 assistant 角色的消息内容作为最终输出。
     *
     * @param instanceId Agent 实例 ID
     * @return 最终输出文本，Agent 未完成或缓存不存在时返回 null
     */
    public String getAgentOutput(String instanceId) {
        AgentInstance instance = instances.get(instanceId);
        if (instance == null) return null;

        if (instance.status() != AgentStatus.DONE && instance.status() != AgentStatus.FAILED) {
            return null;
        }

        // 从缓存中提取最后 assistant 消息
        CacheSession cache = cacheStore.get(instance.cacheId());
        if (cache == null) return null;

        // 从后往前找最后一条 assistant 消息
        List<Map<String, Object>> msgs = cache.messages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = msgs.get(i);
            if ("assistant".equals(msg.get("role"))) {
                Object content = msg.get("content");
                if (content instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * 返回当前管理的 Agent 实例总数。
     *
     * @return Agent 实例数量（包含运行中和已完成的）
     */
    public int agentCount() {
        return instances.size();
    }

    /** 获取配置存储（供工具使用）。 */
    public AgentConfigStore configStore() {
        return configStore;
    }

    // ══════════════════════════════════════════
    // 同步等待（供 DispatchSubAgentTool 内部使用）
    // ══════════════════════════════════════════

    /**
     * 阻塞等待 Agent 完成，返回结果。
     *
     * <p>供 dispatch_sub_agent 工具在同步语义中使用。
     *
     * @param instanceId Agent 实例 ID
     * @param timeoutMs  超时时间（毫秒）
     * @return AgentResult（成功、超时或异常时均返回对应的结果对象）
     */
    public AgentResult waitForCompletion(String instanceId, long timeoutMs) {
        CompletableFuture<AgentResult> future = futures.get(instanceId);
        if (future == null) return AgentResult.fail(instanceId, "Agent not found: " + instanceId);

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancelAgent(instanceId);
            return AgentResult.fail(instanceId, "Agent timed out after " + timeoutMs + "ms");
        } catch (Exception e) {
            return AgentResult.fail(instanceId, "Agent interrupted: " + e.getMessage());
        }
    }

    /**
     * 取消所有正在运行的 Agent。
     *
     * <p>用于全局中断场景（如 ESC 键按下），会传播取消信号到所有运行中的 Agent 及其 Future。
     */
    public void cancelAll() {
        for (var entry : runningAgents.entrySet()) {
            log.info("[AgentsManager] cancelling {}", entry.getKey());
            entry.getValue().cancel();
        }
        for (var entry : futures.entrySet()) {
            entry.getValue().cancel(true);
        }
    }

    // ══════════════════════════════════════════
    // 内部方法
    // ══════════════════════════════════════════

    private void publishEvent(String sessionId, String taskId, String type, Map<String, Object> data) {
        eventBus.publish(GSimEvent.of(sessionId, taskId, type, data));
    }

    private List<LlmMessage> cacheMessagesToLlm(CacheSession session) {
        List<LlmMessage> result = new ArrayList<>();
        for (Map<String, Object> msg : session.messages()) {
            result.add(LlmMessage.fromCacheMap(msg));
        }
        return result;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
