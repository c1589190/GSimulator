package com.gsim.app;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.core.config.ConfigLoader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 AppConfig 消费 ConfigLoader 新增配置键：默认值、目录解析与文件覆盖。
 */
@DisplayName("AppConfig 新配置键读取")
class NewConfigKeysAppConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ConfigLoader 默认值被 AppConfig 逐键消费")
    void defaultsAreConsumed() {
        ConfigLoader loader = new ConfigLoader(new String[] {});
        AppConfig config = new AppConfig(loader.load());

        // Agent ToolLoop 结果回传
        assertEquals(4000, config.resultInlineMaxChars());
        assertTrue(config.resultStagingEnabled());

        // MCP 响应限流与分页
        assertEquals(50000, config.mcpResponseMaxJsonBytes());
        assertEquals(300, config.mcpResponseSnippetMaxChars());
        assertEquals(20, config.mcpResponseDefaultPageSize());
        assertEquals(100, config.mcpResponseMaxPageSize());
        assertTrue(config.mcpResponseOverflowStagingEnabled());

        // 文档暂存与临时目录清理
        assertEquals(500, config.stagingThreshold());
        assertEquals(3000, config.queryStagingThreshold());
        assertEquals(168, config.tmpMaxAgeHours());
        assertTrue(config.tmpCleanupEnabled());

        // Embedding / SubAgent / Import / Web 研究
        assertEquals(30, config.embeddingTimeoutConnectSeconds());
        assertEquals(60, config.embeddingTimeoutReadSeconds());
        assertEquals(30, config.embeddingTimeoutWriteSeconds());
        assertEquals(300, config.subagentCollectTimeoutSeconds());
        assertEquals(100, config.subagentMaxCompleted());
        assertEquals(30000, config.importDocMaxFullReadChars());
        assertEquals(8000, config.importDocDefaultLimit());
        assertEquals("https://en.wikipedia.org/w/api.php", config.wikiUrl());

        // Knowledge DB 默认解析到 baseDir/data/knowledge/gsim.db
        assertTrue(
                config.knowledgeDbPath().endsWith(Path.of("data/knowledge/gsim.db")),
                "默认 knowledgeDbPath 应为 baseDir/data/knowledge/gsim.db，实际 " + config.knowledgeDbPath());
    }

    @Test
    @DisplayName("docs.dir / caches.dir 未设置时回退 worldsDir 同级目录")
    void dirsDefaultToWorldsSibling() {
        ConfigLoader loader = new ConfigLoader(new String[] {});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(config.worldsDir().resolveSibling("docs"), config.docsDir());
        assertEquals(config.worldsDir().resolveSibling("caches"), config.cachesDir());
    }

    @Test
    @DisplayName("配置文件中的 docs.dir / caches.dir / knowledge.db.path 被 AppConfig 读取")
    void dirsAndKnowledgeDbOverridable() throws IOException {
        Path propsFile = tempDir.resolve("dirs.properties");
        writeFile(
                propsFile,
                "llm.base_url=https://api.example.com/v1\n"
                        + "llm.api_key=sk-test\n"
                        + "llm.model=m\n"
                        + "docs.dir=/tmp/xyzdocs\n"
                        + "caches.dir=/tmp/xyzcaches\n"
                        + "knowledge.db.path=/tmp/kb.db\n");

        ConfigLoader loader = new ConfigLoader(new String[] {"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(Path.of("/tmp/xyzdocs"), config.docsDir());
        assertEquals(Path.of("/tmp/xyzcaches"), config.cachesDir());
        assertEquals(Path.of("/tmp/kb.db"), config.knowledgeDbPath());
    }

    @Test
    @DisplayName("agent.tool_loop.max_rounds 回退默认值为 64")
    void maxRoundsFallbackIs64() throws IOException {
        Path propsFile = tempDir.resolve("max-rounds.properties");
        writeFile(
                propsFile,
                "llm.base_url=https://api.example.com/v1\n"
                        + "llm.api_key=sk-test\n"
                        + "llm.model=m\n"
                        + "agent.tool_loop.max_rounds=64\n");
        ConfigLoader loader = new ConfigLoader(new String[] {"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());
        assertEquals(64, config.getAgentToolLoopMaxRounds());
    }

    private static void writeFile(Path file, String content) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(content);
        }
    }
}
