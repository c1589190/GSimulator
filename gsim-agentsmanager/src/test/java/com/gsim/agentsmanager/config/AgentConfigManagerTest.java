package com.gsim.agentsmanager.config;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.AgentConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AgentConfigManager maxToolRounds 校验测试 — 0 = 不限制合法，负数拒绝。
 */
@DisplayName("AgentConfigManager maxToolRounds 校验")
class AgentConfigManagerTest {

    @TempDir
    Path tempDir;

    private AgentConfigStore configStore;
    private AgentConfigManager manager;

    @BeforeEach
    void setUp() throws Exception {
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
        configStore = new AgentConfigStore();
        configStore.reload(agentsDir);
        manager = new AgentConfigManager(configStore, agentsDir);
    }

    @Test
    @DisplayName("maxToolRounds=0（不限制）合法，写入并生效")
    void maxToolRoundsZeroIsAccepted() {
        AgentConfigManager.UpdateResult result = manager.updateAgent("sim", "maxToolRounds", "0");

        assertTrue(result.success(), "0 应为合法值: " + result.message());
        assertEquals(0, configStore.get("sim").maxToolRounds(), "配置应更新为 0");
    }

    @Test
    @DisplayName("maxToolRounds 负数拒绝，原值不变")
    void maxToolRoundsNegativeIsRejected() {
        AgentConfigManager.UpdateResult result = manager.updateAgent("sim", "maxToolRounds", "-1");

        assertFalse(result.success(), "-1 应被拒绝");
        assertTrue(result.message().contains(">= 0"), "错误信息应说明 0 = unlimited: " + result.message());
        assertEquals(259, configStore.get("sim").maxToolRounds(), "失败时原值应保持不变");
    }
}
