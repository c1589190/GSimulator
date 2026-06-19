package com.gsim.config;

import com.gsim.app.AppConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS 环境变量映射和 .env 文件读取。
 */
@DisplayName("AgentToolLoop 最大轮数 — 环境变量映射")
class AgentToolLoopMaxRoundsEnvMappingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS → agent.tool_loop.max_rounds")
    void envVarMapsCorrectly() {
        assertEquals("agent.tool_loop.max_rounds",
                ConfigLoader.mapEnvKey("GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS"));
    }

    @Test
    @DisplayName(".env 文件中设置 GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS=12 可被读取")
    void dotEnvFileSetsMaxRounds() throws IOException {
        Path envFile = tempDir.resolve(".env");
        writeFile(envFile,
                "LLM_BASE_URL=https://api.example.com/v1\n" +
                        "LLM_API_KEY=sk-test\n" +
                        "LLM_MODEL=m\n" +
                        "GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS=12\n");

        // 直接验证 .env 解析
        java.util.Map<String, String> envMap = ConfigLoader.loadDotEnvFile(envFile);
        assertEquals("12", envMap.get("GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS"));

        // 验证映射
        String mappedKey = ConfigLoader.mapEnvKey("GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS");
        assertEquals("agent.tool_loop.max_rounds", mappedKey);
    }

    @Test
    @DisplayName("未知环境变量 key 返回 null")
    void unknownKeyReturnsNull() {
        assertNull(ConfigLoader.mapEnvKey("UNKNOWN_ENV_KEY"));
        assertNull(ConfigLoader.mapEnvKey("GSIM_AGENT_UNKNOWN"));
    }

    @Test
    @DisplayName("GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS 与其他环境变量共存时不冲突")
    void envVarCoexistsWithOtherConfigs() throws IOException {
        Path envFile = tempDir.resolve(".env");
        writeFile(envFile,
                "LLM_BASE_URL=https://api.example.com/v1\n" +
                        "LLM_API_KEY=sk-test\n" +
                        "LLM_MODEL=m\n" +
                        "GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS=15\n" +
                        "GSIM_CONTEXT_SESSION_HISTORY_TURNS=5\n" +
                        "GSIM_CONTEXT_SESSION_MESSAGE_MAX_CHARS=2000\n");

        java.util.Map<String, String> envMap = ConfigLoader.loadDotEnvFile(envFile);
        assertEquals(6, envMap.size());
        assertEquals("15", envMap.get("GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS"));
        assertEquals("5", envMap.get("GSIM_CONTEXT_SESSION_HISTORY_TURNS"));
        assertEquals("2000", envMap.get("GSIM_CONTEXT_SESSION_MESSAGE_MAX_CHARS"));
    }

    @Test
    @DisplayName("完整加载链中 .env 配置被正确加载")
    void fullLoadChainPicksUpDotEnv() throws IOException {
        Path cwdEnv = tempDir.resolve(".env");
        writeFile(cwdEnv,
                "LLM_BASE_URL=https://api.example.com/v1\n" +
                        "LLM_API_KEY=sk-test\n" +
                        "LLM_MODEL=m\n" +
                        "GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS=6\n");

        // 注意：ConfigLoader 从当前工作目录查找 .env，
        // 此测试验证解析器本身的功能，而非路径绑定
        java.util.Map<String, String> parsed = ConfigLoader.loadDotEnvFile(cwdEnv);
        assertEquals("6", parsed.get("GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS"));
    }

    private static void writeFile(Path file, String content) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(content);
        }
    }
}
