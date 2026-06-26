package com.gsim.agent.core;

import com.gsim.agent.AgentProgressSink;
import com.gsim.agent.TaggedAgentProgressSink;
import com.gsim.agent.config.AgentConfigStore;
import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;
import com.gsim.llm.LlmManager;
import com.gsim.llm.LlmMessage;
import com.gsim.llm.LlmProvider;
import com.gsim.llm.LlmProviderRegistry;
import com.gsim.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final LlmManager llm;  // 向后兼容：默认 provider
    private final ToolRegistry allTools;
    private final AgentProgressSink rootSink;
    private final String model;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final Map<String, CompletableFuture<AgentResult>> running = new ConcurrentHashMap<>();
    /** 追踪所有运行中的 AbstractAgent 实例，用于 ESC 取消时设置 cancelRequested。 */
    private final Map<String, AbstractAgent> runningAgents = new ConcurrentHashMap<>();

    /** Cache 文件输出目录（worlds/<worldId>/caches/）。 */
    private final Path worldsDir;
    /** 当前 worldId 提供者（运行时可能切换 world）。 */
    private final java.util.function.Supplier<String> worldIdSupplier;

    public AgentFactory(AgentConfigStore configStore, LlmProviderRegistry llmRegistry,
                        ToolRegistry allTools, AgentProgressSink rootSink, String model,
                        Path worldsDir, java.util.function.Supplier<String> worldIdSupplier) {
        this.configStore = configStore;
        this.llmRegistry = llmRegistry;
        this.llm = (LlmManager) llmRegistry.getDefault();
        this.allTools = allTools;
        this.rootSink = rootSink;
        this.model = model;
        this.worldsDir = worldsDir;
        this.worldIdSupplier = worldIdSupplier;
    }

    /** 创建一个 Agent（阻塞，同步返回） */
    public AbstractAgent create(String agentId, String prompt, Map<String, String> userVars) {
        AgentConfig config = configStore.get(agentId);
        if (config == null) throw new IllegalArgumentException("Unknown agent: " + agentId);

        String systemPrompt = config.effectiveSystemPromptTemplate();
        String userPrompt = prompt != null ? prompt : "";
        if (config.userTemplate() != null && !config.userTemplate().isBlank() && userVars != null) {
            userPrompt = config.renderUserPrompt(userVars);
        }

        // 包装 system + user 为一个完整配置
        AgentConfig fullConfig = new AgentConfig(config.agentId(), config.llmProvider(),
                config.staticSystemPrompt(), systemPrompt, config.systemPrompt(),
                config.userTemplate(), config.toolFilter(),
                config.maxToolRounds(), config.temperature(), config.maxTokens());

        // 创建 tagged sink
        int id = counter.incrementAndGet();
        String instanceId = agentId + "-" + id;
        AgentProgressSink tagged = new TaggedAgentProgressSink(rootSink, instanceId);

        // 按 Agent 配置选择 LLM provider
        LlmProvider agentLlm = llmRegistry.get(config.llmProvider());
        return new AbstractAgent(fullConfig, (LlmManager) agentLlm, allTools, tagged, model);
    }

    /** 异步派发 SubAgent（无 cache — 创建空 cache）。 */
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

        AgentProgressSink tagged = new TaggedAgentProgressSink(rootSink, instanceId, taskId, sessionId);
        AbstractAgent agent = create(type, prompt, null);

        // 加载或创建 CacheSession
        CacheSession subCache;
        List<LlmMessage> priorMessages = List.of();

        if (cacheId != null && !cacheId.isBlank()) {
            subCache = CacheStore.load(worldsDir, wid, cacheId);
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
            CacheStore.appendAndSave(worldsDir, wid, cacheRef,
                    Map.of("role", msg.role(), "content",
                            msg.content() != null ? msg.content() : ""));
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
                        rawResult.agentId(), rawResult.success(), rawResult.finalText(),
                        rawResult.rounds(), rawResult.totalToolCalls(), rawResult.error(),
                        cacheRef.sessionId());
                f.complete(enriched);
            } catch (Exception e) {
                f.complete(AgentResult.fail(instanceId, e.getMessage(), cacheRef.sessionId()));
            } finally {
                runningAgents.remove(instanceId);
            }
        });
        log.info("[AgentFactory] dispatched {} type={} cache={} (prior={} msgs)",
                instanceId, type, cacheRef.sessionId(), finalPrior.size());
        return instanceId;
    }

    /** 将 CacheSession 的 OpenAI 格式消息转换为 LlmMessage 列表。 */
    private static List<LlmMessage> cacheMessagesToLlm(CacheSession session) {
        List<LlmMessage> result = new ArrayList<>();
        for (Map<String, Object> msg : session.messages()) {
            String role = (String) msg.getOrDefault("role", "user");
            String content = (String) msg.getOrDefault("content", "");
            result.add(new LlmMessage(role, content));
        }
        return result;
    }

    /** 等待所有 SubAgent 完成并聚合结果 */
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
            }
            else {
                fail++;
                sb.append("**失败**: ").append(r != null ? r.error() : "无结果").append("\n\n");
                if (r != null && r.cacheSessionId() != null) {
                    sb.append("> cache: `").append(r.cacheSessionId()).append("`\n\n");
                }
            }
        }
        sb.append("---\n").append(ok).append(" 成功, ").append(fail).append(" 失败, ").append(total).append(" 总计");
        running.clear();
        return sb.toString();
    }

    public AgentConfigStore store() { return configStore; }
    public Map<String, CompletableFuture<AgentResult>> running() { return running; }

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
