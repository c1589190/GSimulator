package com.gsim.agent.tool;

import com.gsim.agent.AgentProgressSink;
import com.gsim.agent.config.AgentConfigStore;
import com.gsim.agent.core.AgentFactory;
import com.gsim.agent.core.AgentResult;
import com.gsim.llm.LlmManager;
import com.gsim.llm.LlmProviderRegistry;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

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
        // Write sim config
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("sim.json"), """
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
        Files.writeString(agentsDir.resolve("search.json"), """
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

        var provConfig = com.gsim.llm.ProviderConfig.generic(
                "test", "http://localhost", "key", "test-model", 0.3, 30);
        LlmManager llm = new LlmManager(provConfig);
        LlmProviderRegistry llmRegistry = new LlmProviderRegistry();
        llmRegistry.register("base", llm);
        ToolRegistry tools = new ToolRegistry();
        AgentFactory agentFactory = new AgentFactory(configStore, llmRegistry,
                tools, AgentProgressSink.NOOP, "test-model",
                tempDir, () -> "test-world");

        tool = new DispatchSubAgentTool(llm, tools, "test-model",
                AgentProgressSink.NOOP, runningSubAgents, subAgentCounter,
                agentFactory, configStore);
    }

    @Test
    @DisplayName("已知 agent type (sim) 可成功派发")
    void dispatchKnownTypeSucceeds() {
        ToolCall call = new ToolCall("dispatch_sub_agent", Map.of(
                "type", "sim",
                "prompt", "test prompt"
        ));
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "已知 agent type 应成功派发: " + result.error());
    }

    @Test
    @DisplayName("未知 agent type 应拒绝并列出可用类型")
    void dispatchUnknownTypeRejects() {
        ToolCall call = new ToolCall("dispatch_sub_agent", Map.of(
                "type", "critic",
                "prompt", "test"
        ));
        ToolResult result = tool.execute(call);

        assertFalse(result.success());
        assertTrue(result.error().contains("critic"),
                "错误信息应包含被拒绝的 type");
        assertTrue(result.error().contains("sim"),
                "错误信息应列出可用类型");
    }

    @Test
    @DisplayName("创建新 agent 后 dispatch 应能识别")
    void createThenDispatchNewAgent() throws Exception {
        // Create a new agent config
        Path agentsDir = tempDir.resolve("agents");
        Files.writeString(agentsDir.resolve("critic.json"), """
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

        assertTrue(configStore.agentIds().contains("critic"),
                "reload 后 configStore 应包含新 agent");

        ToolCall call = new ToolCall("dispatch_sub_agent", Map.of(
                "type", "critic",
                "prompt", "review this"
        ));
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "新创建的 agent 应可派发: " + result.error());
    }

    @Test
    @DisplayName("prompt 为空应拒绝")
    void emptyPromptRejects() {
        ToolCall call = new ToolCall("dispatch_sub_agent", Map.of(
                "type", "sim",
                "prompt", ""
        ));
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
