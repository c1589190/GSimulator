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
 * 验证 agent.tool_loop.max_rounds 配置的默认值、clamp 和文件读取。
 */
@DisplayName("AgentToolLoop 最大轮数 — 默认值与文件配置")
class AgentToolLoopMaxRoundsConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("agent.tool_loop.max_rounds 默认值 = 32")
    void defaultMaxRoundsIs32() {
        ConfigLoader loader = new ConfigLoader(new String[]{});
        ConfigLoader.ConfigResult result = loader.load();

        ConfigLoader.ConfigEntry entry = result.entries().get("agent.tool_loop.max_rounds");
        assertNotNull(entry, "agent.tool_loop.max_rounds should exist in defaults");
        if (entry.source() == ConfigSource.DEFAULT) {
            assertEquals("32", entry.value());
        }
    }

    @Test
    @DisplayName("AppConfig.getAgentToolLoopMaxRounds() 默认返回 32")
    void appConfigDefaultMaxRounds() {
        ConfigLoader loader = new ConfigLoader(new String[]{});
        AppConfig config = new AppConfig(loader.load());
        assertEquals(32, config.getAgentToolLoopMaxRounds());
    }

    @Test
    @DisplayName("配置文件设置 agent.tool_loop.max_rounds=12 后 AppConfig 返回 12")
    void configFileSetsMaxRoundsTo12() throws IOException {
        Path propsFile = tempDir.resolve("toolmax.properties");
        writeFile(propsFile,
                "llm.base_url=https://api.example.com/v1\n" +
                        "llm.api_key=sk-test\n" +
                        "llm.model=m\n" +
                        "agent.tool_loop.max_rounds=12\n");

        ConfigLoader loader = new ConfigLoader(new String[]{"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(12, config.getAgentToolLoopMaxRounds());
    }

    @Test
    @DisplayName("大值 999 生效（无上限）")
    void largeValue999Accepted() throws IOException {
        Path propsFile = tempDir.resolve("toohigh.properties");
        writeFile(propsFile,
                "llm.base_url=https://api.example.com/v1\n" +
                        "llm.api_key=sk-test\n" +
                        "llm.model=m\n" +
                        "agent.tool_loop.max_rounds=999\n");

        ConfigLoader loader = new ConfigLoader(new String[]{"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(999, config.getAgentToolLoopMaxRounds(),
                "999 should be accepted (no upper clamp)");
    }

    @Test
    @DisplayName("非法值 0 应 clamp 到下限 1")
    void illegalValue0ClampedTo1() throws IOException {
        Path propsFile = tempDir.resolve("toolow.properties");
        writeFile(propsFile,
                "llm.base_url=https://api.example.com/v1\n" +
                        "llm.api_key=sk-test\n" +
                        "llm.model=m\n" +
                        "agent.tool_loop.max_rounds=0\n");

        ConfigLoader loader = new ConfigLoader(new String[]{"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(1, config.getAgentToolLoopMaxRounds(),
                "0 should be clamped to min 1");
    }

    @Test
    @DisplayName("负值应 clamp 到下限 1")
    void negativeValueClampedTo1() throws IOException {
        Path propsFile = tempDir.resolve("negative.properties");
        writeFile(propsFile,
                "llm.base_url=https://api.example.com/v1\n" +
                        "llm.api_key=sk-test\n" +
                        "llm.model=m\n" +
                        "agent.tool_loop.max_rounds=-5\n");

        ConfigLoader loader = new ConfigLoader(new String[]{"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(1, config.getAgentToolLoopMaxRounds(),
                "-5 should be clamped to min 1");
    }

    @Test
    @DisplayName("下限 1 可正确设置")
    void minBoundaryWorks() throws IOException {
        Path propsFile1 = tempDir.resolve("min.properties");
        writeFile(propsFile1,
                "llm.base_url=https://api.example.com/v1\nllm.api_key=sk\nllm.model=m\n" +
                        "agent.tool_loop.max_rounds=1\n");
        ConfigLoader l1 = new ConfigLoader(new String[]{"--config", propsFile1.toString()});
        assertEquals(1, new AppConfig(l1.load()).getAgentToolLoopMaxRounds());
    }

    private static void writeFile(Path file, String content) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(content);
        }
    }
}
