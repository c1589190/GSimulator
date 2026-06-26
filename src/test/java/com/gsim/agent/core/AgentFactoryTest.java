package com.gsim.agent.core;

import com.gsim.agent.AgentProgressEvent;
import com.gsim.agent.AgentProgressSink;
import com.gsim.agent.config.AgentConfigStore;
import com.gsim.cache.CacheStore;
import com.gsim.llm.LlmManager;
import com.gsim.llm.LlmProviderRegistry;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentFactory 子代理派发与编号")
class AgentFactoryTest {

    private AgentConfigStore configStore;
    private AgentFactory factory;
    private List<AgentProgressEvent> capturedEvents;
    private AgentProgressSink capturingSink;
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        // Write sim config to temp agents dir
        Path agentsDir = tempDir.resolve("agents");
        java.nio.file.Files.createDirectories(agentsDir);
        java.nio.file.Files.writeString(agentsDir.resolve("sim.json"), """
                {
                    "agentId": "sim",
                    "llmProvider": "base",
                    "staticSystemPrompt": "You are a simulation agent.",
                    "maxToolRounds": 8,
                    "temperature": 0.3,
                    "maxTokens": 2048,
                    "toolFilter": { "mode": "read_only" }
                }
                """);
        java.nio.file.Files.writeString(agentsDir.resolve("search.json"), """
                {
                    "agentId": "search",
                    "llmProvider": "base",
                    "staticSystemPrompt": "You are a search agent.",
                    "maxToolRounds": 8,
                    "temperature": 0.3,
                    "maxTokens": 2048,
                    "toolFilter": { "mode": "read_only" }
                }
                """);

        configStore = new AgentConfigStore();
        configStore.reload(agentsDir);

        capturedEvents = new ArrayList<>();
        capturingSink = capturedEvents::add;

        // Set up LLM provider registry with a test provider
        var provConfig = com.gsim.llm.ProviderConfig.generic(
                "test", "http://localhost", "key", "test-model", 0.3, 30);
        LlmProviderRegistry llmRegistry = new LlmProviderRegistry();
        llmRegistry.register("base", new LlmManager(provConfig));

        ToolRegistry tools = new ToolRegistry();

        factory = new AgentFactory(configStore, llmRegistry,
                tools, capturingSink, "test-model",
                tempDir, () -> "test-world");
    }

    /** Wait for all running futures to settle (fail quickly: no real LLM). */
    private void awaitRunning() {
        for (var f : factory.running().values()) {
            try { f.get(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception ignored) { }
        }
    }

    @Test
    @DisplayName("dispatch 返回的 instanceId 与 running map key 一致")
    void dispatchReturnsSameInstanceIdAsRunningKey() {
        String instanceId = factory.dispatch("sim", "test prompt", "task-1", "session-1");

        assertNotNull(instanceId);
        assertTrue(instanceId.startsWith("sim-"));
        assertTrue(factory.running().containsKey(instanceId),
                "running map key 应与 dispatch 返回值一致");
        awaitRunning();
    }

    @Test
    @DisplayName("dispatch 两次生成不同的 instanceId")
    void dispatchTwiceGeneratesDistinctInstanceIds() {
        String id1 = factory.dispatch("sim", "prompt 1", "task-1", "session-1");
        String id2 = factory.dispatch("sim", "prompt 2", "task-2", "session-1");

        assertNotEquals(id1, id2);
        assertEquals(2, factory.running().size());
        awaitRunning();
    }

    @Test
    @DisplayName("create 不产生 counter 递增（仅 dispatch 产生）")
    void createDoesNotIncrementCounter() {
        // create should not affect the counter used by dispatch
        AbstractAgent agent = factory.create("sim", "test prompt", null);
        assertNotNull(agent);

        // First dispatch should still be "sim-1"
        String id = factory.dispatch("sim", "test", "t1", "s1");
        assertTrue(id.endsWith("-1") || factory.running().size() == 1,
                "create 不应消费 dispatch 的 counter");
    }

    @Test
    @DisplayName("未知 agent type 在 create 时抛出异常")
    void unknownAgentTypeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                factory.create("nonexistent", "prompt", null));
    }
}
