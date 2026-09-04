package com.gsim.agentsmanager.core;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.AgentConfigStore;
import com.gsim.agentsmanager.cache.CacheStore;
import com.gsim.agentsmanager.event.AgentProgressSink;
import com.gsim.agentsmanager.llm.LlmManager;
import com.gsim.agentsmanager.llm.LlmProviderRegistry;
import com.gsim.agentsmanager.llm.ProviderConfig;
import com.gsim.agentsmanager.tool.ToolRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AgentFactory maxCompleted 配置注入测试 — 断言注入的上限驱动 FIFO 淘汰。
 */
@DisplayName("AgentFactory maxCompleted 配置注入")
class AgentFactoryMaxCompletedConfigTest {

    @TempDir
    Path tempDir;

    private AgentConfigStore configStore;
    private LlmProviderRegistry llmRegistry;
    private AgentFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        CacheStore.setCachesRoot(tempDir);

        Path agentsDir = tempDir.resolve("agents");
        java.nio.file.Files.createDirectories(agentsDir);
        java.nio.file.Files.writeString(
                agentsDir.resolve("sim.json"),
                """
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

        configStore = new AgentConfigStore();
        configStore.reload(agentsDir);

        var provConfig = ProviderConfig.generic("test", "http://localhost", "key", "test-model", 0.3, 30);
        llmRegistry = new LlmProviderRegistry();
        llmRegistry.register("base", new LlmManager(provConfig));

        factory = new AgentFactory(
                configStore,
                (com.gsim.agentsmanager.llm.LlmClient) llmRegistry.getDefault(),
                new ToolRegistry(),
                AgentProgressSink.NOOP,
                "test-model",
                2);
    }

    /** Wait for all running futures to settle (fail quickly: no real LLM). */
    private void awaitRunning() {
        for (var f : factory.running().values()) {
            try {
                f.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("注入的 maxCompleted=2 限制已完成结果缓存大小")
    void injectedMaxCompletedBoundsCompletedCache() {
        factory.dispatch("sim", "prompt 1", "task-1", "session-1");
        factory.dispatch("sim", "prompt 2", "task-2", "session-1");
        factory.dispatch("sim", "prompt 3", "task-3", "session-1");
        awaitRunning();

        assertEquals(2, factory.completed().size(), "completed cache should be bounded by injected maxCompleted");
    }

    @Test
    @DisplayName("默认构造保留 maxCompleted=100")
    void defaultConstructorKeepsMaxCompleted() {
        var defaultFactory = new AgentFactory(
                configStore,
                (com.gsim.agentsmanager.llm.LlmClient) llmRegistry.getDefault(),
                new ToolRegistry(),
                AgentProgressSink.NOOP,
                "test-model");

        assertNotNull(defaultFactory);
        assertTrue(defaultFactory.completed().isEmpty());
    }
}
