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
 * 验证 context session 配置项的环境变量映射和属性文件读取。
 */
@DisplayName("ContextSession 环境变量映射")
class ContextSessionHistoryTurnsEnvMappingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("GSIM_CONTEXT_SESSION_HISTORY_TURNS → context.session.history.turns")
    void envVarMapsToHistoryTurns() {
        assertEquals("context.session.history.turns",
                ConfigLoader.mapEnvKey("GSIM_CONTEXT_SESSION_HISTORY_TURNS"));
    }

    @Test
    @DisplayName("GSIM_CONTEXT_SESSION_MESSAGE_MAX_CHARS → context.session.message.max_chars")
    void envVarMapsToMessageMaxChars() {
        assertEquals("context.session.message.max_chars",
                ConfigLoader.mapEnvKey("GSIM_CONTEXT_SESSION_MESSAGE_MAX_CHARS"));
    }

    @Test
    @DisplayName("属性文件中的 context.session.history.turns 应被 AppConfig 读取")
    void propertiesFileHistoryTurnsReadByAppConfig() throws IOException {
        Path propsFile = tempDir.resolve("ctx.properties");
        writeFile(propsFile,
                "llm.base_url=https://api.example.com/v1\n" +
                        "llm.api_key=sk-test\n" +
                        "llm.model=m\n" +
                        "context.session.history.turns=8\n" +
                        "context.session.message.max_chars=6000\n");

        ConfigLoader loader = new ConfigLoader(new String[]{"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(8, config.getContextSessionHistoryTurns());
        assertEquals(6000, config.getContextSessionMessageMaxChars());
    }

    @Test
    @DisplayName("只设置 history.turns 时 max_chars 应保持默认值")
    void onlyHistoryTurnsSetMaxCharsStaysDefault() throws IOException {
        Path propsFile = tempDir.resolve("partial.properties");
        writeFile(propsFile,
                "llm.base_url=https://api.example.com/v1\n" +
                        "llm.api_key=sk-test\n" +
                        "llm.model=m\n" +
                        "context.session.history.turns=3\n");

        ConfigLoader loader = new ConfigLoader(new String[]{"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(3, config.getContextSessionHistoryTurns());
        assertEquals(4000, config.getContextSessionMessageMaxChars(),
                "messageMaxChars should stay at default 4000");
    }

    @Test
    @DisplayName("只设置 message.max_chars 时 history.turns 应保持默认值")
    void onlyMessageMaxCharsSetHistoryTurnsStaysDefault() throws IOException {
        Path propsFile = tempDir.resolve("partial2.properties");
        writeFile(propsFile,
                "llm.base_url=https://api.example.com/v1\n" +
                        "llm.api_key=sk-test\n" +
                        "llm.model=m\n" +
                        "context.session.message.max_chars=8000\n");

        ConfigLoader loader = new ConfigLoader(new String[]{"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(12, config.getContextSessionHistoryTurns(),
                "historyTurns should stay at default 12");
        assertEquals(8000, config.getContextSessionMessageMaxChars());
    }

    @Test
    @DisplayName("min 边界值应生效")
    void minBoundaryValues() throws IOException {
        Path propsFile = tempDir.resolve("min.properties");
        writeFile(propsFile,
                "llm.base_url=https://api.example.com/v1\n" +
                        "llm.api_key=sk-test\n" +
                        "llm.model=m\n" +
                        "context.session.history.turns=1\n" +
                        "context.session.message.max_chars=500\n");

        ConfigLoader loader = new ConfigLoader(new String[]{"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(1, config.getContextSessionHistoryTurns());
        assertEquals(500, config.getContextSessionMessageMaxChars());
    }

    @Test
    @DisplayName("max 边界值应生效")
    void maxBoundaryValues() throws IOException {
        Path propsFile = tempDir.resolve("max.properties");
        writeFile(propsFile,
                "llm.base_url=https://api.example.com/v1\n" +
                        "llm.api_key=sk-test\n" +
                        "llm.model=m\n" +
                        "context.session.history.turns=50\n" +
                        "context.session.message.max_chars=20000\n");

        ConfigLoader loader = new ConfigLoader(new String[]{"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(50, config.getContextSessionHistoryTurns());
        assertEquals(20000, config.getContextSessionMessageMaxChars());
    }

    @Test
    @DisplayName("OrchestratorAgent.ContextHistoryConfig 从 AppConfig 正确构造")
    void contextHistoryConfigFromAppConfig() throws IOException {
        Path propsFile = tempDir.resolve("orchestrator.properties");
        writeFile(propsFile,
                "llm.base_url=https://api.example.com/v1\n" +
                        "llm.api_key=sk-test\n" +
                        "llm.model=m\n" +
                        "context.session.history.turns=7\n" +
                        "context.session.message.max_chars=3000\n");

        ConfigLoader loader = new ConfigLoader(new String[]{"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        com.gsim.agent.OrchestratorAgent.ContextHistoryConfig historyConfig =
                new com.gsim.agent.OrchestratorAgent.ContextHistoryConfig(
                        config.getContextSessionHistoryTurns(),
                        config.getContextSessionMessageMaxChars());

        assertEquals(7, historyConfig.historyTurns());
        assertEquals(3000, historyConfig.messageMaxChars());
    }

    private static void writeFile(Path file, String content) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(content);
        }
    }
}
