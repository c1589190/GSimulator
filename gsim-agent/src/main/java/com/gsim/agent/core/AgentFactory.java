package com.gsim.agent.core;

import com.gsim.agent.AgentConfig;
import com.gsim.agent.AgentConfigStore;
import com.gsim.agent.TaggedAgentProgressSink;
import com.gsim.agentlib.tool.ToolRegistry;
import com.gsim.core.cache.CacheSession;
import com.gsim.core.cache.CacheStore;
import com.gsim.core.event.AgentProgressSink;
import com.gsim.core.llm.LlmManager;
import com.gsim.core.llm.LlmMessage;
import com.gsim.core.llm.LlmProvider;
import com.gsim.core.llm.LlmProviderRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 工厂 — 根据 AgentConfig 创建 AbstractAgent 实例。
 *
 * <p>取代硬编码的 SimAgent/SearchAgent 类。
 * SubAgent 通过 agentId + prompt 动态创建。
 */
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final AgentConfigStore configStore;
    private final LlmProviderRegistry llmRegistry;
    private final LlmManager llm; // 向后兼容：默认 provider
    private final ToolRegistry allTools;
    private final AgentProgressSink rootSink;
    private final String model;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final Map<String, CompletableFuture<AgentResult>> running = new ConcurrentHashMap<>();
    /** 追踪所有运行中的 AbstractAgent 实例，用于 ESC 取消时设置 cancelRequested。 */
    private final Map<String, AbstractAgent> runningAgents = new ConcurrentHashMap<>();
    /** 已完成子代理的结果缓存（FIFO 淘汰，上限由构造参数注入，默认 100）。 */
    private final Map<String, AgentResult> completed = new ConcurrentHashMap<>();

    private final int maxCompleted;

    /** 工具结果反馈策略（null = 遗留行为：截断到 500，不暂存）。 */
    private final AbstractAgent.ToolResultPolicy resultPolicy;

    /** Cache 文件输出目录（worlds/<worldId>/caches/）。 */
    private final Path worldsDir;
    /** 当前 worldId 提供者（运行时可能切换 world）。 */
    private final java.util.function.Supplier<String> worldIdSupplier;

    /**
     * 创建 AgentFactory 实例。
     *
     * @param configStore    Agent 配置存储
     * @param llmRegistry    LLM provider 注册表
     * @param allTools       全局工具注册表
     * @param rootSink       根进度事件接收器
     * @param model          默认模型名称
     * @param worldsDir      Cache 文件输出目录
     * @param worldIdSupplier 当前 worldId 提供者
     */
    public AgentFactory(
            AgentConfigStore configStore,
            LlmProviderRegistry llmRegistry,
            ToolRegistry allTools,
            AgentProgressSink rootSink,
            String model,
            Path worldsDir,
            java.util.function.Supplier<String> worldIdSupplier) {
        this(configStore, llmRegistry, allTools, rootSink, model, worldsDir, worldIdSupplier, 100);
    }

    /**
     * 创建 AgentFactory 实例。
     *
     * @param configStore    Agent 配置存储
     * @param llmRegistry    LLM provider 注册表
     * @param allTools       全局工具注册表
     * @param rootSink       根进度事件接收器
     * @param model          默认模型名称
     * @param worldsDir      Cache 文件输出目录
     * @param worldIdSupplier 当前 worldId 提供者
     * @param maxCompleted   已完成结果缓存上限（FIFO 淘汰）
     */
    public AgentFactory(
            AgentConfigStore configStore,
            LlmProviderRegistry llmRegistry,
            ToolRegistry allTools,
            AgentProgressSink rootSink,
            String model,
            Path worldsDir,
            java.util.function.Supplier<String> worldIdSupplier,
            int maxCompleted) {
        this(configStore, llmRegistry, allTools, rootSink, model, worldsDir, worldIdSupplier, maxCompleted, null);
    }

    /**
     * 创建 AgentFactory 实例。
     *
     * @param configStore    Agent 配置存储
     * @param llmRegistry    LLM provider 注册表
     * @param allTools       全局工具注册表
     * @param rootSink       根进度事件接收器
     * @param model          默认模型名称
     * @param worldsDir      Cache 文件输出目录
     * @param worldIdSupplier 当前 worldId 提供者
     * @param maxCompleted   已完成结果缓存上限（FIFO 淘汰）
     * @param resultPolicy   工具结果反馈策略（null = 遗留行为：截断到 500，不暂存）
     */
    public AgentFactory(
            AgentConfigStore configStore,
            LlmProviderRegistry llmRegistry,
            ToolRegistry allTools,
            AgentProgressSink rootSink,
            String model,
            Path worldsDir,
            java.util.function.Supplier<String> worldIdSupplier,
            int maxCompleted,
            AbstractAgent.ToolResultPolicy resultPolicy) {
        this.configStore = configStore;
        this.llmRegistry = llmRegistry;
        this.llm = (LlmManager) llmRegistry.getDefault();
        this.allTools = allTools;
        this.rootSink = rootSink;
        this.model = model;
        this.worldsDir = worldsDir;
        this.worldIdSupplier = worldIdSupplier;
        this.maxCompleted = maxCompleted;
        this.resultPolicy = resultPolicy;
    }

    /**
     * 创建一个 Agent（阻塞，同步返回）。
     *
     * <p>从 {@link AgentConfigStore} 加载指定 agentId 的配置，
     * 按配置选择对应的 LLM provider，并返回新的 {@link AbstractAgent} 实例。
     * 不创建 {@code TaggedAgentProgressSink} — 由调用方（如 dispatch）注入。
     *
     * @param agentId  Agent 类型标识符（如 "orchestrator"、"sim"）
     * @param prompt   用户输入提示词
     * @param userVars 用户模板变量（用于渲染 userTemplate），可为 null
     * @return 新创建的 AbstractAgent 实例
     * @throws IllegalArgumentException 当 agentId 未在配置存储中注册时抛出
     */
    public AbstractAgent create(String agentId, String prompt, Map<String, String> userVars) {
        AgentConfig config = configStore.get(agentId);
        if (config == null) throw new IllegalArgumentException("Unknown agent: " + agentId);

        String userPrompt = prompt != null ? prompt : "";
        if (config.userTemplate() != null && !config.userTemplate().isBlank() && userVars != null) {
            userPrompt = config.renderUserPrompt(userVars);
        }

        // 直接使用原始 config（系统提示词已是静态完整内容）
        AgentConfig fullConfig = config;

        // 按 Agent 配置选择 LLM provider
        LlmProvider agentLlm = llmRegistry.get(config.llmProvider());
        // 使用 rootSink — 调用方可随后 replaceProgressSink()
        return new AbstractAgent(fullConfig, (LlmManager) agentLlm, allTools, rootSink, model, resultPolicy);
    }

    /**
     * 异步派发 SubAgent（无 cache — 创建空 cache）。
     *
     * @param type      agent 类型（sim/search）
     * @param prompt    任务指令
     * @param taskId    任务 ID（用于事件路由）
     * @param sessionId 会话 ID（用于事件路由）
     * @return 分配的 SubAgent 实例 ID（如 "sim-1"）
     */
    public String dispatch(String type, String prompt, String taskId, String sessionId) {
        return dispatch(type, prompt, taskId, sessionId, null);
    }

    /**
     * 异步派发 SubAgent，支持加载已有 cache 续接上下文。
     *
     * @param type     agent 类型（sim/search）
     * @param prompt   任务指令
     * @param taskId   任务 ID（用于事件路由）
     * @param sessionId 会话 ID（用于事件路由）
     * @param cacheId  可选 — 要加载的已有 cache sessionId，null = 创建空 cache
     */
    public String dispatch(String type, String prompt, String taskId, String sessionId, String cacheId) {
        int id = counter.incrementAndGet();
        String instanceId = type + "-" + id;
        String wid = worldIdSupplier.get();

        AbstractAgent agent = create(type, prompt, null);
        // Replace the rootSink from create() with a properly tagged sink
        AgentProgressSink tagged = new TaggedAgentProgressSink(rootSink, instanceId, taskId, sessionId);
        agent.replaceProgressSink(tagged);
        // Override agentId so logs show "sim-1" instead of "sim"
        agent.setAgentId(instanceId);

        // 加载或创建 CacheSession
        CacheSession subCache;
        List<LlmMessage> priorMessages = List.of();

        if (cacheId != null && !cacheId.isBlank()) {
            subCache = CacheStore.load(worldsDir, cacheId);
            if (subCache != null) {
                log.info("[AgentFactory] reusing cache {} for {}", cacheId, instanceId);
                // 将历史消息转换为 LlmMessage 列表
                priorMessages = cacheMessagesToLlm(subCache);
            } else {
                log.warn("[AgentFactory] cache not found: {}, creating new", cacheId);
                subCache = CacheStore.createNew(worldsDir, wid, instanceId, "n0000");
            }
        } else {
            subCache = CacheStore.createNew(worldsDir, wid, instanceId, "n0000");
        }

        // 设置 write-through 持久化
        final CacheSession cacheRef = subCache;
        agent.setMessageSaver(msg -> {
            CacheStore.appendAndSave(worldsDir, cacheRef, msg.toCacheMap());
        });

        CompletableFuture<AgentResult> f = new CompletableFuture<>();
        running.put(instanceId, f);
        runningAgents.put(instanceId, agent);

        final List<LlmMessage> finalPrior = priorMessages;
        Thread.startVirtualThread(() -> {
            try {
                AgentResult rawResult = agent.run(prompt, finalPrior);
                // 注入 cacheSessionId
                AgentResult enriched = new AgentResult(
                        rawResult.agentId(),
                        rawResult.success(),
                        rawResult.finalText(),
                        rawResult.rounds(),
                        rawResult.totalToolCalls(),
                        rawResult.error(),
                        cacheRef.sessionId());
                f.complete(enriched);
            } catch (Exception e) {
                f.complete(AgentResult.fail(instanceId, e.getMessage(), cacheRef.sessionId()));
            } finally {
                runningAgents.remove(instanceId);
                // Move from running to completed cache (bounded FIFO)
                AgentResult result = f.getNow(null);
                if (result != null) {
                    if (completed.size() >= maxCompleted) {
                        // Simple FIFO: remove one arbitrary entry
                        var it = completed.keySet().iterator();
                        if (it.hasNext()) {
                            completed.remove(it.next());
                        }
                    }
                    completed.put(instanceId, result);
                }
            }
        });
        log.info(
                "[AgentFactory] dispatched {} type={} cache={} (prior={} msgs)",
                instanceId,
                type,
                cacheRef.sessionId(),
                finalPrior.size());
        return instanceId;
    }

    /** 将 CacheSession 的 OpenAI 格式消息转换为 LlmMessage 列表。 */
    private static List<LlmMessage> cacheMessagesToLlm(CacheSession session) {
        List<LlmMessage> result = new ArrayList<>();
        for (Map<String, Object> msg : session.messages()) {
            result.add(LlmMessage.fromCacheMap(msg));
        }
        return result;
    }

    /**
     * 等待所有 SubAgent 完成并聚合结果。
     *
     * <p>阻塞等待直到所有运行中的 SubAgent 完成或超时。
     * 聚合每个 SubAgent 的成功/失败状态、最终文本和 cache 引用。
     *
     * @param timeoutMs 等待超时时间（毫秒）
     * @return 格式化的聚合结果字符串（Markdown）
     */
    public String collectAll(long timeoutMs) {
        if (running.isEmpty()) return "没有正在运行的子代理。";

        CompletableFuture<?>[] futures = running.values().toArray(CompletableFuture[]::new);
        int total = futures.length;
        try {
            CompletableFuture.allOf(futures).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("[AgentFactory] collect timeout/error: {}", e.getMessage());
        }

        StringBuilder sb = new StringBuilder("## 子代理执行结果\n\n");
        int ok = 0, fail = 0;
        for (var e : running.entrySet()) {
            AgentResult r = e.getValue().getNow(null);
            sb.append("### ").append(e.getKey()).append("\n\n");
            if (r != null && r.success()) {
                ok++;
                sb.append(r.finalText()).append("\n\n");
                // 输出 cache 引用
                if (r.cacheSessionId() != null) {
                    sb.append("> cache: `").append(r.cacheSessionId()).append("`\n\n");
                }
            } else {
                fail++;
                sb.append("**失败**: ").append(r != null ? r.error() : "无结果").append("\n\n");
                if (r != null && r.cacheSessionId() != null) {
                    sb.append("> cache: `").append(r.cacheSessionId()).append("`\n\n");
                }
            }
        }
        sb.append("---\n")
                .append(ok)
                .append(" 成功, ")
                .append(fail)
                .append(" 失败, ")
                .append(total)
                .append(" 总计");
        running.clear();
        return sb.toString();
    }

    public AgentConfigStore store() {
        return configStore;
    }

    public Map<String, CompletableFuture<AgentResult>> running() {
        return running;
    }

    /** 已完成结果缓存（包可见，测试断言 maxCompleted 淘汰用）。 */
    Map<String, AgentResult> completed() {
        return completed;
    }

    /** 取消所有正在运行的 SubAgent（设置 cancelRequested 标志 + 中断线程）。 */
    public void cancelAll() {
        for (var entry : runningAgents.entrySet()) {
            log.info("[AgentFactory] cancelling sub-agent {}", entry.getKey());
            entry.getValue().cancel();
        }
        // 同时取消所有 future（让等待者立刻感知）
        for (var entry : running.entrySet()) {
            entry.getValue().cancel(true);
        }
    }
}
