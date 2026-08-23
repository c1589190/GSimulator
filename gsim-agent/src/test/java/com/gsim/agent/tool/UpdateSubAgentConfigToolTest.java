package com.gsim.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.agent.AgentConfigStore;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("UpdateSubAgentConfigTool tool_filter 校验")
class UpdateSubAgentConfigToolTest {

    @TempDir
    Path tempDir;

    private Path agentsDir;
    private UpdateSubAgentConfigTool tool;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(
                agentsDir.resolve("analyst.json"),
                """
                {
                    "agentId": "analyst",
                    "llmProvider": "base",
                    "staticSystemPrompt": "You are an analyst.",
                    "maxToolRounds": 259,
                    "temperature": 0.3,
                    "maxTokens": 2048,
                    "toolFilter": { "mode": "read_only" }
                }
                """);
        AgentConfigStore configStore = new AgentConfigStore();
        configStore.reload(agentsDir);
        tool = new UpdateSubAgentConfigTool(agentsDir, configStore);
    }

    @Test
    @DisplayName("tool_filter=none 合法：read_only → none 成功，config 中 toolFilter.mode == none")
    void updateToNoneSucceeds() throws Exception {
        ToolResult result = tool.execute(new ToolCall(
                "update_sub_agent_config",
                Map.of(
                        "agent_id", "analyst",
                        "tool_filter", "none")));
        assertTrue(result.success(), "tool_filter=none 应成功: " + result.error());

        Map<String, Object> config = mapper.readValue(
                Files.readString(agentsDir.resolve("analyst.json")), new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> toolFilter = (Map<String, Object>) config.get("toolFilter");
        assertNotNull(toolFilter, "config 应包含 toolFilter");
        assertEquals("none", toolFilter.get("mode"), "toolFilter.mode 应为 none");
    }

    @Test
    @DisplayName("tool_filter=bogus 仍拒绝")
    void updateWithBogusRejects() {
        ToolResult result = tool.execute(new ToolCall(
                "update_sub_agent_config",
                Map.of(
                        "agent_id", "analyst",
                        "tool_filter", "bogus")));
        assertFalse(result.success(), "tool_filter=bogus 应失败");
        assertTrue(result.error().contains("bogus"), "错误消息应包含被拒绝的值: " + result.error());
        assertTrue(result.error().contains("可选: all, read_only, custom, none"), "错误消息应列出 none: " + result.error());
    }
}
