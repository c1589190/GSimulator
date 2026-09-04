package com.gsim.agentsmanager.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.AgentConfigStore;
import com.gsim.agentsmanager.AgentInstance;
import com.gsim.agentsmanager.AgentStatus;
import com.gsim.agentsmanager.core.AgentFactory;
import com.gsim.agentsmanager.management.AgentCacheStore;
import com.gsim.agentsmanager.management.AgentsManager;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolRegistry;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.agentsmanager.cache.CacheSession;
import com.gsim.agentsmanager.cache.CacheStore;
import com.gsim.agentsmanager.cache.CachesManager;
import com.gsim.agentsmanager.cache.FileSystemCachesManager;
import com.gsim.agentsmanager.event.AgentProgressSink;
import com.gsim.agentsmanager.event.EventBus;
import com.gsim.agentsmanager.llm.LlmCall;
import com.gsim.agentsmanager.llm.LlmManager;
import com.gsim.agentsmanager.llm.LlmProviderRegistry;
import com.gsim.agentsmanager.llm.LlmRequest;
import com.gsim.agentsmanager.llm.LlmResult;
import com.gsim.agentsmanager.llm.ProviderConfig;
import com.gsim.agentsmanager.llm.StreamPool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 异步子代理工具集成测试 — AgentsManager 路径。
 *
 * <p>覆盖：dispatch 立即返回 RUNNING、collect 非阻塞已完成列表（含 type 过滤）、
 * view cache 状态标记（🔄 运行中 / ✅ 已完成 / 📄 历史缓存）。
 */
@DisplayName("异步子代理工具（AgentsManager 路径）")
class AsyncSubAgentToolsTest {

    @TempDir
    Path tempDir;

    private AgentsManager agentsManager;
    private DispatchSubAgentTool dispatchTool;
    private CollectSubAgentResultsTool collectTool;
    private ViewSubAgentCacheTool viewTool;
    private FakeLlm llm;

    /** 可编程 Fake LLM — submit() 返回脚本化流式结果，避免真实网络调用。 */
    static class FakeLlm extends LlmManager {
        volatile Function<LlmRequest, LlmResult> handler = req -> LlmResult.success("default", "m", 0);
        private final AtomicInteger callCounter = new AtomicInteger();

        FakeLlm() {
            super(ProviderConfig.generic("test", "http://127.0.0.1:1", "k", "m", 0.3, 30));
        }

        @Override
        public LlmCall submit(LlmRequest request) {
            LlmResult result = handler.apply(request);
            StreamPool pool = new StreamPool("fake-" + callCounter.incrementAndGet());
            if (result.success() && result.content() != null) {
                pool.onContentDelta(result.content());
            }
            pool.onComplete(result);
            return new LlmCall("fake", pool);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        CacheStore.setCachesRoot(tempDir.resolve("caches"));

        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(
                agentsDir.resolve("sim.json"),
                """
                {
                    "agentId": "sim",
                    "llmProvider": "base",
                    "staticSystemPrompt": "You are a sim agent.",
                    "maxToolRounds": 259,
                    "temperature": 0.3,
                    "maxTokens": 2048,
                    "toolFilter": { "mode": "read_only" }
                }
                """);
        Files.writeString(
                agentsDir.resolve("search.json"),
                """
                {
                    "agentId": "search",
                    "llmProvider": "base",
                    "staticSystemPrompt": "You are a search agent.",
                    "maxToolRounds": 259,
                    "temperature": 0.3,
                    "maxTokens": 2048,
                    "toolFilter": { "mode": "read_only" }
                }
                """);

        AgentConfigStore configStore = new AgentConfigStore();
        configStore.reload(agentsDir);

        llm = new FakeLlm();
        LlmProviderRegistry llmRegistry = new LlmProviderRegistry();
        llmRegistry.register("base", llm);
        ToolRegistry tools = new ToolRegistry();

        Path cachesDir = tempDir.resolve("caches");
        var agentCacheStore = new AgentCacheStore(cachesDir, configStore);
        agentCacheStore.init();

        agentsManager = new AgentsManager(
                configStore,
                agentCacheStore,
                llmRegistry,
                tools,
                AgentProgressSink.NOOP,
                new EventBus(),
                "test-model",
                new com.gsim.agentsmanager.core.AbstractAgent.ToolResultPolicy(500, false, null, ""));

        CachesManager cachesManager = new FileSystemCachesManager();

        AgentFactory agentFactory =
                new AgentFactory(configStore, llmRegistry, tools, AgentProgressSink.NOOP, "test-model");
        dispatchTool = new DispatchSubAgentTool(
                llm,
                tools,
                "test-model",
                AgentProgressSink.NOOP,
                new ConcurrentHashMap<>(),
                new AtomicInteger(),
                agentFactory,
                configStore,
                null);
        dispatchTool.setAgentsManager(agentsManager);

        collectTool = new CollectSubAgentResultsTool(new ConcurrentHashMap<>());
        collectTool.setAgentsManager(agentsManager);

        viewTool = new ViewSubAgentCacheTool(cachesManager);
        viewTool.setAgentsManager(agentsManager);
    }

    private AgentInstance awaitDone(String instanceId) throws InterruptedException {
        AgentInstance inst = agentsManager.getAgent(instanceId);
        long deadline = System.currentTimeMillis() + 10_000;
        while (inst.status() == AgentStatus.RUNNING || inst.status() == AgentStatus.PENDING) {
            if (System.currentTimeMillis() > deadline) {
                fail("agent " + instanceId + " did not finish within 10s");
            }
            Thread.sleep(20);
            inst = agentsManager.getAgent(instanceId);
        }
        return inst;
    }

    @Test
    @DisplayName("dispatch 通过 AgentsManager 异步派发：立即返回 RUNNING + cacheId，不阻塞")
    void dispatchReturnsImmediatelyWithRunningStatus() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        llm.handler = req -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return LlmResult.failure("interrupted");
            }
            return LlmResult.success("done", "m", 0);
        };

        long start = System.nanoTime();
        ToolResult result = dispatchTool.execute(
                new ToolCall("dispatch_sub_agent", Map.of("type", "sim", "prompt", "background task")));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.success(), "异步派发应立即成功: " + result.error());
        assertTrue(elapsedMs < 2000, "派发不应阻塞等待子代理，耗时 " + elapsedMs + "ms");

        ToolResult.Item item = result.items().get(0);
        String agentId = item.title().substring("sub_agent_dispatched: ".length());
        AgentInstance inst = agentsManager.getAgent(agentId);
        assertNotNull(inst, "派发后实例应存在");
        assertEquals(AgentStatus.RUNNING, inst.status(), "派发后状态应为 RUNNING");
        assertTrue(item.snippet().contains("RUNNING"), "snippet 应标记 RUNNING");
        assertTrue(item.snippet().contains(inst.cacheId()), "snippet 应包含 cacheId");

        release.countDown();
        assertEquals(AgentStatus.DONE, awaitDone(agentId).status(), "释放后子代理应正常完成");
    }

    @Test
    @DisplayName("collect 非阻塞列出已完成子代理，type 过滤生效")
    void collectListsDoneAgentsWithTypeFilter() throws Exception {
        llm.handler = req -> LlmResult.success("最终推演文本", "m", 0);

        AgentInstance sim = agentsManager.runAgent("sim", null, "推演", null);
        AgentInstance search = agentsManager.runAgent("search", null, "搜索", null);
        awaitDone(sim.instanceId());
        awaitDone(search.instanceId());

        ToolResult all = collectTool.execute(new ToolCall("collect_sub_agent_results", Map.of()));
        assertTrue(all.success());
        String allSnippet = all.items().get(0).snippet();
        assertTrue(allSnippet.contains(sim.instanceId()), "应包含 sim 实例: " + allSnippet);
        assertTrue(allSnippet.contains(search.instanceId()), "应包含 search 实例: " + allSnippet);
        assertTrue(allSnippet.contains("最终推演文本"), "应包含最终结果摘要: " + allSnippet);
        assertTrue(allSnippet.contains("轮数"), "应包含轮数统计: " + allSnippet);

        ToolResult simOnly = collectTool.execute(new ToolCall("collect_sub_agent_results", Map.of("type", "sim")));
        String simSnippet = simOnly.items().get(0).snippet();
        assertTrue(simSnippet.contains(sim.instanceId()));
        assertFalse(simSnippet.contains(search.instanceId()), "type=sim 不应包含 search 结果");

        ToolResult none =
                collectTool.execute(new ToolCall("collect_sub_agent_results", Map.of("type", "orchestrator")));
        assertEquals("no_completed_sub_agents", none.items().get(0).title(), "无匹配类型时应返回空列表提示");
    }

    @Test
    @DisplayName("view_sub_agent_cache 状态标记：已完成 → ✅ + 最终结果")
    void viewCacheMarksDone() throws Exception {
        llm.handler = req -> LlmResult.success("已完成的结果文本", "m", 0);

        AgentInstance inst = agentsManager.runAgent("sim", null, "任务", null);
        awaitDone(inst.instanceId());

        ToolResult result = viewTool.execute(new ToolCall("view_sub_agent_cache", Map.of("cacheId", inst.cacheId())));
        assertTrue(result.success(), "查看已完成缓存应成功: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("✅ 已完成"), "应标记已完成: " + snippet);
        assertTrue(snippet.contains("最终结果"), "应包含最终结果: " + snippet);
        assertTrue(snippet.contains("已完成的结果文本"), "最终结果应为子代理输出: " + snippet);
    }

    @Test
    @DisplayName("view_sub_agent_cache 状态标记：运行中 → 🔄 + 最后 3 条交互")
    void viewCacheMarksRunning() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        llm.handler = req -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return LlmResult.failure("interrupted");
            }
            return LlmResult.success("done", "m", 0);
        };

        AgentInstance inst = agentsManager.runAgent("sim", null, "任务", null);

        ToolResult result = viewTool.execute(new ToolCall("view_sub_agent_cache", Map.of("cacheId", inst.cacheId())));
        assertTrue(result.success(), "查看运行中缓存应成功: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("🔄 运行中"), "应标记运行中: " + snippet);
        assertTrue(snippet.contains("完整对话"), "运行中应展示完整对话: " + snippet);

        release.countDown();
        awaitDone(inst.instanceId());
    }

    @Test
    @DisplayName("view_sub_agent_cache 状态标记：无实例 → 📄 历史缓存")
    void viewCacheMarksHistorical() {
        CacheSession historical = CacheStore.createNew("hist");
        CacheStore.appendAndSave(historical, Map.of("role", "user", "content", "历史请求"));

        ToolResult result =
                viewTool.execute(new ToolCall("view_sub_agent_cache", Map.of("cacheId", historical.sessionId())));
        assertTrue(result.success());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("📄 历史缓存"), "无运行实例应标记历史缓存: " + snippet);
    }
}
