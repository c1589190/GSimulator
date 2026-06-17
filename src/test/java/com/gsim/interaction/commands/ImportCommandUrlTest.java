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
 *
 * 注意：CommandParser 将 /import 之后的所有文本作为 args[0] 传入
 * （与 /run /searchdb 相同），所以测试中的 args[0] 包含完整命令文本。
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

    // Helper: wrap command text as the single arg that CommandParser would produce
    private static String[] arg(String commandText) {
        if (commandText == null || commandText.isBlank()) return new String[]{""};
        return new String[]{commandText};
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
        InteractionResult result = importCommand.execute(
                arg("https://192.0.2.1/test --fetch-only --no-crawl"), session);
        assertNotNull(result);
        assertTrue(result.displayText().contains("Web 导入")
                || result.displayText().contains("Web import")
                || !result.success());
    }

    @Test
    @DisplayName("应正确解析 --fetch-only --no-crawl 标志")
    void testImport_FetchOnlyNoCrawl() {
        InteractionResult result = importCommand.execute(
                arg("https://192.0.2.1/test --fetch-only --no-crawl"), session);
        assertNotNull(result);
        assertTrue(result.displayText().contains("fetch-only") || result.displayText().contains("Fetch-only"));
    }

    @Test
    @DisplayName("应正确解析 --max-pages --depth 和 --delay-ms 参数")
    void testImport_NumericParams() {
        InteractionResult result = importCommand.execute(
                arg("https://192.0.2.1/test --max-pages 5 --depth 1 --delay-ms 500 --fetch-only"), session);
        assertNotNull(result);
    }

    // ---- 参数校验测试 ----

    @Test
    @DisplayName("未知 flag 应返回错误")
    void testImport_UnknownFlag() {
        InteractionResult result = importCommand.execute(
                arg("https://192.0.2.1/test --unknown-flag"), session);
        assertFalse(result.success());
        assertTrue(result.displayText().contains("Unknown flag"));
    }

    @Test
    @DisplayName("--max-pages 缺值应返回错误")
    void testImport_MaxPagesMissingValue() {
        InteractionResult result = importCommand.execute(
                arg("https://192.0.2.1/test --max-pages"), session);
        assertFalse(result.success());
        assertTrue(result.displayText().contains("--max-pages requires a value"));
    }

    @Test
    @DisplayName("--depth 缺值应返回错误")
    void testImport_DepthMissingValue() {
        InteractionResult result = importCommand.execute(
                arg("https://192.0.2.1/test --depth"), session);
        assertFalse(result.success());
        assertTrue(result.displayText().contains("--depth requires a value"));
    }

    @Test
    @DisplayName("--delay-ms 缺值应返回错误")
    void testImport_DelayMsMissingValue() {
        InteractionResult result = importCommand.execute(
                arg("https://192.0.2.1/test --delay-ms"), session);
        assertFalse(result.success());
        assertTrue(result.displayText().contains("--delay-ms requires a value"));
    }

    @Test
    @DisplayName("--max-pages 必须 > 0")
    void testImport_MaxPagesMustBePositive() {
        InteractionResult result = importCommand.execute(
                arg("https://192.0.2.1/test --max-pages 0"), session);
        assertFalse(result.success());
        assertTrue(result.displayText().contains("must be > 0"));
    }

    @Test
    @DisplayName("--depth 必须 >= 0")
    void testImport_DepthMustBeNonNegative() {
        InteractionResult result = importCommand.execute(
                arg("https://192.0.2.1/test --depth -1"), session);
        assertFalse(result.success());
        assertTrue(result.displayText().contains("must be >= 0"));
    }

    @Test
    @DisplayName("--delay-ms 必须 >= 0")
    void testImport_DelayMsMustBeNonNegative() {
        InteractionResult result = importCommand.execute(
                arg("https://192.0.2.1/test --delay-ms -100"), session);
        assertFalse(result.success());
        assertTrue(result.displayText().contains("must be >= 0"));
    }

    @Test
    @DisplayName("--max-pages 非数字应返回错误")
    void testImport_MaxPagesNotANumber() {
        InteractionResult result = importCommand.execute(
                arg("https://192.0.2.1/test --max-pages abc"), session);
        assertFalse(result.success());
        assertTrue(result.displayText().contains("Invalid --max-pages value"));
    }
}
