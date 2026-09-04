package com.gsim.agentsmanager.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.AgentConfigStore;
import com.gsim.agentsmanager.core.AgentFactory;
import com.gsim.agentsmanager.core.AgentResult;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolRegistry;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.cache.CacheStore;
import com.gsim.core.event.AgentProgressSink;
import com.gsim.core.llm.LlmManager;
import com.gsim.core.llm.LlmProviderRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("DispatchSubAgentTool 动态 agent type 校验")
class DispatchSubAgentToolTest {

    @TempDir
    Path tempDir;

    private AgentConfigStore configStore;
    private DispatchSubAgentTool tool;
    private Map<String, CompletableFuture<AgentResult>> runningSubAgents;
    private AtomicInteger subAgentCounter;

    @BeforeEach
    void setUp() throws Exception {
        CacheStore.setCachesRoot(tempDir);

        // Write sim config
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(
                agentsDir.resolve("sim.json"),
                """
                {
                    "agentId": "sim",
                    "llmProvider": "base",
                    "staticSystemPrompt": "You are a sim agent.",
                    "maxToolRounds": 8,
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
                    "maxToolRounds": 8,
                    "temperature": 0.3,
                    "maxTokens": 2048,
                    "toolFilter": { "mode": "read_only" }
                }
                """);

        configStore = new AgentConfigStore();
        configStore.reload(agentsDir);

        runningSubAgents = new ConcurrentHashMap<>();
        subAgentCounter = new AtomicInteger(0);

        var provConfig =
                com.gsim.core.llm.ProviderConfig.generic("test", "http://localhost", "key", "test-model", 0.3, 30);
        LlmManager llm = new LlmManager(provConfig);
        LlmProviderRegistry llmRegistry = new LlmProviderRegistry();
        llmRegistry.register("base", llm);
        ToolRegistry tools = new ToolRegistry();
        AgentFactory agentFactory =
                new AgentFactory(configStore, llmRegistry, tools, AgentProgressSink.NOOP, "test-model");

        tool = new DispatchSubAgentTool(
                llm,
                tools,
                "test-model",
                AgentProgressSink.NOOP,
                runningSubAgents,
                subAgentCounter,
                agentFactory,
                configStore,
                null);
    }

    @Test
    @DisplayName("已知 agent type (sim) 异步派发立即返回，不阻塞等待结果")
    void dispatchKnownTypeReturnsImmediately() throws Exception {
        ToolCall call = new ToolCall(
                "dispatch_sub_agent",
                Map.of(
                        "type", "sim",
                        "prompt", "test prompt"));
        long start = System.nanoTime();
        ToolResult result = tool.execute(call);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 异步语义：派发后立即成功返回 agentId + RUNNING，而不是等待子代理完成
        assertTrue(result.success(), "异步派发应立即成功: " + result.error());
        assertEquals(1, result.items().size());
        ToolResult.Item item = result.items().get(0);
        assertTrue(item.title().startsWith("sub_agent_dispatched: "), "title 应为派发确认而非结果: " + item.title());
        assertTrue(item.snippet().contains("RUNNING"), "snippet 应含 RUNNING 状态: " + item.snippet());
        assertTrue(
                item.snippet().contains(item.title().substring("sub_agent_dispatched: ".length())),
                "snippet 应含 agentId");
        assertTrue(elapsedMs < 2000, "派发不应阻塞等待子代理，耗时 " + elapsedMs + "ms");

        // 等待后台子代理结束（连接拒绝快速失败），避免 @TempDir 清理时其仍在写入缓存
        long deadline = System.currentTimeMillis() + 10_000;
        while (runningSubAgents.values().stream().anyMatch(f -> !f.isDone()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("未知 agent type 应拒绝并列出可用类型")
    void dispatchUnknownTypeRejects() {
        ToolCall call = new ToolCall(
                "dispatch_sub_agent",
                Map.of(
                        "type", "critic",
                        "prompt", "test"));
        ToolResult result = tool.execute(call);

        assertFalse(result.success());
        assertTrue(result.error().contains("critic"), "错误信息应包含被拒绝的 type");
        assertTrue(result.error().contains("sim"), "错误信息应列出可用类型");
    }

    @Test
    @DisplayName("创建新 agent 后 dispatch 校验通过（不再报 Unknown type）")
    void createThenDispatchNewAgent() throws Exception {
        // Create a new agent config
        Path agentsDir = tempDir.resolve("agents");
        Files.writeString(
                agentsDir.resolve("critic.json"),
                """
                {
                    "agentId": "critic",
                    "llmProvider": "base",
                    "staticSystemPrompt": "You are a critic agent.",
                    "maxToolRounds": 8,
                    "temperature": 0.3,
                    "maxTokens": 2048,
                    "toolFilter": { "mode": "read_only" }
                }
                """);
        configStore.reload(agentsDir);

        assertTrue(configStore.agentIds().contains("critic"), "reload 后 configStore 应包含新 agent");

        ToolCall call = new ToolCall(
                "dispatch_sub_agent",
                Map.of(
                        "type", "critic",
                        "prompt", "review this"));
        ToolResult result = tool.execute(call);

        // Should NOT be rejected as unknown type
        assertFalse(result.error().contains("Unknown sub-agent type"), "新创建的 agent 应通过校验: " + result.error());
    }

    @Test
    @DisplayName("orchestrator 类型应被显式拒绝（即使存在于 configStore）")
    void dispatchOrchestratorTypeRejects() throws Exception {
        // orchestrator 是内置 agent，在 configStore 中真实存在——但作为子代理类型必须被显式排除
        Path agentsDir = tempDir.resolve("agents");
        Files.writeString(
                agentsDir.resolve("orchestrator.json"),
                """
                {
                    "agentId": "orchestrator",
                    "llmProvider": "base",
                    "staticSystemPrompt": "You are the orchestrator.",
                    "maxToolRounds": 8,
                    "temperature": 0.3,
                    "maxTokens": 2048,
                    "toolFilter": { "mode": "all" }
                }
                """);
        configStore.reload(agentsDir);

        assertTrue(configStore.agentIds().contains("orchestrator"), "reload 后 configStore 应包含 orchestrator");

        ToolCall call = new ToolCall(
                "dispatch_sub_agent",
                Map.of(
                        "type", "orchestrator",
                        "prompt", "test"));
        ToolResult result = tool.execute(call);

        assertFalse(result.success(), "orchestrator 不应被允许作为子代理派发");
        assertTrue(result.error().contains("orchestrator"), "错误信息应包含被拒绝的 type: " + result.error());
        assertTrue(result.error().contains("Available"), "错误信息应列出可用类型: " + result.error());
        assertFalse(result.error().contains("Available: orchestrator"), "可用类型列表不应包含 orchestrator");
    }

    @Test
    @DisplayName("prompt 为空应拒绝")
    void emptyPromptRejects() {
        ToolCall call = new ToolCall(
                "dispatch_sub_agent",
                Map.of(
                        "type", "sim",
                        "prompt", ""));
        ToolResult result = tool.execute(call);

        assertFalse(result.success());
        assertTrue(result.error().contains("prompt"));
    }

    @Test
    @DisplayName("getParameters 应包含动态 enum")
    void getParametersContainsDynamicEnum() {
        Map<String, Object> params = tool.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> typeDef = (Map<String, Object>) params.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> typeField = (Map<String, Object>) typeDef.get("type");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) typeField.get("enum");

        assertNotNull(enumValues);
        assertTrue(enumValues.contains("sim"), "enum 应包含 sim");
        assertTrue(enumValues.contains("search"), "enum 应包含 search");
    }
}
