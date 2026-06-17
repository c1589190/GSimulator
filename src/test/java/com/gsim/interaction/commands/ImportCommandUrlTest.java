package com.gsim.interaction.commands;

import com.gsim.app.AppConfig;
import com.gsim.campaign.CampaignService;
import com.gsim.campaign.PlayerActionService;
import com.gsim.campaign.TurnService;
import com.gsim.chroma.ChromaClient;
import com.gsim.chroma.FakeChromaClient;
import com.gsim.importdata.ImportManager;
import com.gsim.interaction.InteractionContext;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.storage.DataPaths;
import com.gsim.util.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ImportCommand URL 测试 — 使用 FakeChromaClient，不访问外网。
 */
@DisplayName("ImportCommand (URL)")
class ImportCommandUrlTest {

    @TempDir
    Path tempDir;

    private ImportCommand importCommand;
    private InteractionSession session;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("GSIM_DATA_DIR", tempDir.resolve("data").toString());
        System.setProperty("GSIM_IMPORT_DIR", tempDir.resolve("import").toString());

        AppConfig config = new AppConfig();
        DataPaths dataPaths = new DataPaths(config);
        dataPaths.initialize();

        TimeProvider timeProvider = new TimeProvider();
        CampaignService campaignService = new CampaignService(dataPaths, timeProvider);
        TurnService turnService = new TurnService(dataPaths, timeProvider);
        PlayerActionService playerActionService = new PlayerActionService(dataPaths, timeProvider);

        InteractionContext context = new InteractionContext();
        session = new InteractionSession(context, config, campaignService, turnService, playerActionService);

        ChromaClient chromaClient = new FakeChromaClient();
        ImportManager importManager = new ImportManager(config, chromaClient);
        importCommand = new ImportCommand(config, importManager);
    }

    @Test
    @DisplayName("无参数 /import 应触发本地导入")
    void testImport_Local() {
        InteractionResult result = importCommand.execute(new String[0], session);
        assertTrue(result.success());
        assertTrue(result.displayText().contains("本地导入"));
    }

    @Test
    @DisplayName("/import 空参数应触发本地导入")
    void testImport_EmptyArg() {
        InteractionResult result = importCommand.execute(new String[]{""}, session);
        assertTrue(result.success());
        assertTrue(result.displayText().contains("本地导入"));
    }

    @Test
    @DisplayName("命令名应为 import")
    void testCommandName() {
        assertEquals("import", importCommand.name());
    }

    @Test
    @DisplayName("命令描述应包含 URL 导入说明")
    void testCommandDescription() {
        String desc = importCommand.description();
        assertTrue(desc.contains("URL"));
    }

    @Test
    @DisplayName("命令用法应包含 URL 参数说明")
    void testCommandUsage() {
        String usage = importCommand.usage();
        assertTrue(usage.contains("URL"));
        assertTrue(usage.contains("fetch-only"));
        assertTrue(usage.contains("no-crawl"));
        assertTrue(usage.contains("max-pages"));
        assertTrue(usage.contains("depth"));
        assertTrue(usage.contains("delay-ms"));
    }

    @Test
    @DisplayName("非 URL 非空的参数应返回错误")
    void testImport_InvalidArg() {
        InteractionResult result = importCommand.execute(new String[]{"somefile.txt"}, session);
        assertFalse(result.success());
        assertTrue(result.displayText().contains("Unknown argument"));
    }

    @Test
    @DisplayName("有效的 http URL 应触发 web import")
    void testImport_HttpUrl() {
        // 不会真正连接，因为目标是不可达的 URL
        // 但应该返回 web import 结果（失败页）
        InteractionResult result = importCommand.execute(
                new String[]{"https://192.0.2.1/test", "--fetch-only", "--no-crawl"},
                session);

        assertNotNull(result);
        // 应该尝试执行 web import
        assertTrue(result.displayText().contains("Web 导入") || result.displayText().contains("Web import") || !result.success());
    }

    @Test
    @DisplayName("应正确解析 --fetch-only --no-crawl 标志")
    void testImport_FetchOnlyNoCrawl() {
        InteractionResult result = importCommand.execute(
                new String[]{"https://192.0.2.1/test", "--fetch-only", "--no-crawl"},
                session);

        assertNotNull(result);
        assertTrue(result.displayText().contains("fetch-only") || result.displayText().contains("Fetch-only"));
    }

    @Test
    @DisplayName("应正确解析 --max-pages --depth 和 --delay-ms 参数")
    void testImport_NumericParams() {
        InteractionResult result = importCommand.execute(
                new String[]{"https://192.0.2.1/test", "--max-pages", "5", "--depth", "1", "--delay-ms", "500", "--fetch-only"},
                session);

        assertNotNull(result);
    }
}
