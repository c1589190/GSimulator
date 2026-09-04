package com.gsim.agentsmanager.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.AgentConfigStore;
import com.gsim.agentsmanager.AgentInstance;
import com.gsim.agentsmanager.AgentStatus;
import com.gsim.agentsmanager.management.AgentCacheStore;
import com.gsim.agentsmanager.management.AgentsManager;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.cache.CacheStore;
import com.gsim.core.event.AgentProgressSink;
import com.gsim.core.event.EventBus;
import com.gsim.core.llm.LlmCall;
import com.gsim.core.llm.LlmManager;
import com.gsim.core.llm.LlmProviderRegistry;
import com.gsim.core.llm.LlmRequest;
import com.gsim.core.llm.LlmResult;
import com.gsim.core.llm.ProviderConfig;
import com.gsim.core.llm.StreamPool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * stop_sub_agent 工具测试 — AgentsManager 路径。
 *
 * <p>覆盖：停止运行中的子代理 → CANCELLED；未知/已完成 agentId → fail；AgentsManager 未注入 → fail。
 */
@DisplayName("stop_sub_agent 工具（AgentsManager 路径）")
class StopSubAgentToolTest {

    @TempDir
    Path tempDir;

    private AgentsManager agentsManager;
    private StopSubAgentTool stopTool;
    private FakeLlm llm;

    /** 可编程 Fake LLM — 与 AsyncSubAgentToolsTest 同模式。 */
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

        AgentConfigStore configStore = new AgentConfigStore();
        configStore.reload(agentsDir);

        llm = new FakeLlm();
        LlmProviderRegistry llmRegistry = new LlmProviderRegistry();
        llmRegistry.register("base", llm);

        Path cachesDir = tempDir.resolve("caches");
        var agentCacheStore = new AgentCacheStore(cachesDir, configStore);
        agentCacheStore.init();

        agentsManager = new AgentsManager(
                configStore,
                agentCacheStore,
                llmRegistry,
                new com.gsim.agentsmanager.tool.ToolRegistry(),
                AgentProgressSink.NOOP,
                new EventBus(),
                "test-model",
                new com.gsim.agentsmanager.core.AbstractAgent.ToolResultPolicy(500, false, null, ""));

        stopTool = new StopSubAgentTool();
        stopTool.setAgentsManager(agentsManager);
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
    @DisplayName("停止运行中的子代理 → success + 状态 CANCELLED")
    void stopRunningAgentMarksCancelled() throws Exception {
        CountDownLatch llmEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        llm.handler = req -> {
            llmEntered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return LlmResult.failure("interrupted");
            }
            return LlmResult.success("done", "m", 0);
        };

        AgentInstance inst = agentsManager.runAgent("sim", null, "任务", null);
        assertTrue(llmEntered.await(10, TimeUnit.SECONDS), "子代理应已进入 LLM 调用并阻塞");

        ToolResult result = stopTool.execute(new ToolCall("stop_sub_agent", Map.of("agentId", inst.instanceId())));
        assertTrue(result.success(), "停止运行中的子代理应成功: " + result.error());
        assertEquals("stopped: " + inst.instanceId(), result.items().get(0).title());
        assertEquals(
                AgentStatus.CANCELLED, agentsManager.getAgent(inst.instanceId()).status(), "状态应为 CANCELLED");

        release.countDown();
    }

    @Test
    @DisplayName("未知 agentId → fail")
    void stopUnknownAgentFails() {
        ToolResult result = stopTool.execute(new ToolCall("stop_sub_agent", Map.of("agentId", "sim-999")));

        assertFalse(result.success(), "未知 agent 应失败");
        assertTrue(result.error().contains("不存在") || result.error().contains("不在运行"), "应说明原因: " + result.error());
    }

    @Test
    @DisplayName("已完成的 agent → fail（不在运行中）")
    void stopDoneAgentFails() throws Exception {
        llm.handler = req -> LlmResult.success("done", "m", 0);
        AgentInstance inst = agentsManager.runAgent("sim", null, "任务", null);
        assertEquals(AgentStatus.DONE, awaitDone(inst.instanceId()).status());

        ToolResult result = stopTool.execute(new ToolCall("stop_sub_agent", Map.of("agentId", inst.instanceId())));
        assertFalse(result.success(), "已完成的 agent 不应被停止");
        assertTrue(result.error().contains("不在运行"), "应说明原因: " + result.error());
    }

    @Test
    @DisplayName("AgentsManager 未注入 → fail")
    void stopWithoutAgentsManagerFails() {
        StopSubAgentTool bare = new StopSubAgentTool();
        ToolResult result = bare.execute(new ToolCall("stop_sub_agent", Map.of("agentId", "sim-1")));

        assertFalse(result.success(), "未注入时应失败");
        assertTrue(result.error().contains("AgentsManager 未注入"), "应提示未注入: " + result.error());
    }
}
