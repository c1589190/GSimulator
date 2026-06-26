package com.gsim.agent.core;

import com.gsim.agent.AgentProgressSink;
import com.gsim.agent.TaggedAgentProgressSink;
import com.gsim.agent.config.AgentConfigStore;
import com.gsim.llm.LlmManager;
import com.gsim.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
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
    private final LlmManager llm;
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

    public AgentFactory(AgentConfigStore configStore, LlmManager llm, ToolRegistry allTools,
                        AgentProgressSink rootSink, String model,
                        Path worldsDir, java.util.function.Supplier<String> worldIdSupplier) {
        this.configStore = configStore;
        this.llm = llm;
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

        String systemPrompt = config.systemPrompt();
        String userPrompt = prompt != null ? prompt : "";
        if (config.userTemplate() != null && !config.userTemplate().isBlank() && userVars != null) {
            userPrompt = config.renderUserPrompt(userVars);
        }

        // 包装 system + user 为一个完整配置
        AgentConfig fullConfig = new AgentConfig(config.agentId(), systemPrompt,
                config.userTemplate(), config.toolFilter(),
                config.maxToolRounds(), config.temperature(), config.maxTokens());

        // 创建 tagged sink
        int id = counter.incrementAndGet();
        String instanceId = agentId + "-" + id;
        AgentProgressSink tagged = new TaggedAgentProgressSink(rootSink, instanceId);

        return new AbstractAgent(fullConfig, llm, allTools, tagged, model);
    }

    /** 异步派发 SubAgent（OrchAgent 通过 dispatch_sub_agent 工具调用） */
    public String dispatch(String type, String prompt, String taskId, String sessionId) {
        int id = counter.incrementAndGet();
        String instanceId = type + "-" + id;

        AgentProgressSink tagged = new TaggedAgentProgressSink(rootSink, instanceId, taskId, sessionId);
        AbstractAgent agent = create(type, prompt, null);

        // 为子代理创建独立的 CacheSession（与主 Agent 的 cache 分离）
        String wid = worldIdSupplier.get();
        var subCache = com.gsim.cache.CacheStore.createNew(worldsDir, wid, instanceId,
                /* nodeId: 使用主 Agent 的当前节点，子代理 cache 文件中记录 */ "n0000");
        agent.setMessageSaver(msg -> {
            com.gsim.cache.CacheStore.appendAndSave(worldsDir, wid, subCache,
                    java.util.Map.of("role", msg.role(), "content",
                            msg.content() != null ? msg.content() : ""));
        });

        CompletableFuture<AgentResult> f = new CompletableFuture<>();
        running.put(instanceId, f);
        runningAgents.put(instanceId, agent);

        Thread.startVirtualThread(() -> {
            try {
                f.complete(agent.run(prompt));
            } catch (Exception e) {
                f.complete(AgentResult.fail(instanceId, e.getMessage()));
            } finally {
                runningAgents.remove(instanceId);
            }
        });
        log.info("[AgentFactory] dispatched {} type={} cache={}",
                instanceId, type, subCache.sessionId());
        return instanceId;
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
            if (r != null && r.success()) { ok++; sb.append(r.finalText()).append("\n\n"); }
            else { fail++; sb.append("**失败**: ").append(r != null ? r.error() : "无结果").append("\n\n"); }
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
