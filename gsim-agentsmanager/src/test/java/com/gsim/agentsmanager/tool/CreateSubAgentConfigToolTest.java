package com.gsim.agentsmanager.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.agentsmanager.AgentConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("CreateSubAgentConfigTool tool_filter 校验")
class CreateSubAgentConfigToolTest {

    @TempDir
    Path tempDir;

    private Path agentsDir;
    private CreateSubAgentConfigTool tool;

    @BeforeEach
    void setUp() throws Exception {
        agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);
        AgentConfigStore configStore = new AgentConfigStore();
        configStore.reload(agentsDir);
        tool = new CreateSubAgentConfigTool(agentsDir, configStore);
    }

    private ToolResult executeCreate(String toolFilter) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("agent_id", "mute");
        params.put("llm_provider", "base");
        params.put("system_prompt", "You are a silent agent.");
        if (toolFilter != null) {
            params.put("tool_filter", toolFilter);
        }
        return tool.execute(new ToolCall("create_sub_agent_config", params));
    }

    @Test
    @DisplayName("tool_filter=none 合法：创建成功，写入的 config 中 toolFilter.mode == none")
    void createWithNoneSucceeds() throws Exception {
        ToolResult result = executeCreate("none");
        assertTrue(result.success(), "tool_filter=none 应成功: " + result.error());

        Path configFile = agentsDir.resolve("mute.json");
        assertTrue(Files.exists(configFile), "配置应写入 agents 目录");

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> config =
                mapper.readValue(Files.readString(configFile), new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> toolFilter = (Map<String, Object>) config.get("toolFilter");
        assertNotNull(toolFilter, "config 应包含 toolFilter");
        assertEquals("none", toolFilter.get("mode"), "toolFilter.mode 应为 none");
    }

    @Test
    @DisplayName("tool_filter=bogus 仍拒绝，错误消息列出 none")
    void createWithBogusRejects() {
        ToolResult result = executeCreate("bogus");
        assertFalse(result.success(), "tool_filter=bogus 应失败");
        assertTrue(result.error().contains("可选: all, read_only, custom, none"), "错误消息应列出 none: " + result.error());
    }
}
