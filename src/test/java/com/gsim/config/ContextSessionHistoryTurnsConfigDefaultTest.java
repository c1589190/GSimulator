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
 * 验证 context.session.history.turns 和 context.session.message.max_chars 默认值。
 */
@DisplayName("ContextSession 历史配置默认值")
class ContextSessionHistoryTurnsConfigDefaultTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("context.session.history.turns 默认值 = 12")
    void defaultHistoryTurnsIs12() {
        ConfigLoader loader = new ConfigLoader(new String[]{});
        ConfigLoader.ConfigResult result = loader.load();

        ConfigLoader.ConfigEntry entry = result.entries().get("context.session.history.turns");
        assertNotNull(entry, "context.session.history.turns should exist in defaults");
        if (entry.source() == ConfigSource.DEFAULT) {
            assertEquals("12", entry.value(),
                    "default history turns should be 12");
        }
    }

    @Test
    @DisplayName("context.session.message.max_chars 默认值 = 4000")
    void defaultMessageMaxCharsIs4000() {
        ConfigLoader loader = new ConfigLoader(new String[]{});
        ConfigLoader.ConfigResult result = loader.load();

        ConfigLoader.ConfigEntry entry = result.entries().get("context.session.message.max_chars");
        assertNotNull(entry, "context.session.message.max_chars should exist in defaults");
        if (entry.source() == ConfigSource.DEFAULT) {
            assertEquals("4000", entry.value(),
                    "default message max chars should be 4000");
        }
    }

    @Test
    @DisplayName("AppConfig.getContextSessionHistoryTurns() 默认返回 12")
    void appConfigDefaultHistoryTurns() {
        ConfigLoader loader = new ConfigLoader(new String[]{});
        AppConfig config = new AppConfig(loader.load());
        assertEquals(12, config.getContextSessionHistoryTurns());
    }

    @Test
    @DisplayName("AppConfig.getContextSessionMessageMaxChars() 默认返回 4000")
    void appConfigDefaultMessageMaxChars() {
        ConfigLoader loader = new ConfigLoader(new String[]{});
        AppConfig config = new AppConfig(loader.load());
        assertEquals(4000, config.getContextSessionMessageMaxChars());
    }

    @Test
    @DisplayName("非法值应 fallback 默认值（clamp）")
    void illegalValuesClamped() throws IOException {
        Path propsFile = tempDir.resolve("bad-context.properties");
        writeFile(propsFile,
                "llm.base_url=https://api.example.com/v1\n" +
                        "llm.api_key=sk-test\n" +
                        "llm.model=m\n" +
                        "context.session.history.turns=999\n" +
                        "context.session.message.max_chars=10\n");

        ConfigLoader loader = new ConfigLoader(new String[]{"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        // historyTurns clamped to max 50
        assertEquals(50, config.getContextSessionHistoryTurns(),
                "Illegal 999 should be clamped to 50");
        // messageMaxChars clamped to min 500
        assertEquals(500, config.getContextSessionMessageMaxChars(),
                "Illegal 10 should be clamped to 500");
    }

    private static void writeFile(Path file, String content) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(content);
        }
    }
}
