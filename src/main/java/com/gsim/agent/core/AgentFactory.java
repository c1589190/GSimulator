package com.gsim.agent.core;

import com.gsim.agent.AgentProgressSink;
import com.gsim.agent.TaggedAgentProgressSink;
import com.gsim.agent.config.AgentConfigStore;
import com.gsim.llm.LlmManager;
import com.gsim.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public AgentFactory(AgentConfigStore configStore, LlmManager llm, ToolRegistry allTools,
                        AgentProgressSink rootSink, String model) {
        this.configStore = configStore;
        this.llm = llm;
        this.allTools = allTools;
        this.rootSink = rootSink;
        this.model = model;
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

        CompletableFuture<AgentResult> f = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try { f.complete(agent.run(prompt)); }
            catch (Exception e) { f.complete(AgentResult.fail(instanceId, e.getMessage())); }
        });
        running.put(instanceId, f);
        log.info("[AgentFactory] dispatched {} type={}", instanceId, type);
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
}
