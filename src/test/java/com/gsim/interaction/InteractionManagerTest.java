package com.gsim.interaction;

import com.gsim.app.AppConfig;
import com.gsim.campaign.CampaignService;
import com.gsim.campaign.PlayerActionService;
import com.gsim.campaign.TurnService;
import com.gsim.interaction.commands.ExitCommand;
import com.gsim.interaction.commands.HelpCommand;
import com.gsim.interaction.commands.StatusCommand;
import com.gsim.storage.DataPaths;
import com.gsim.util.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InteractionManager 测试。
 */
@DisplayName("InteractionManager")
class InteractionManagerTest {

    @TempDir
    Path tempDir;

    private InteractionManager manager;
    private InteractionSession session;

    @BeforeEach
    void setUp() throws Exception {
        // Setup with temp directory
        System.setProperty("GSIM_DATA_DIR", tempDir.resolve("data").toString());
        System.setProperty("GSIM_IMPORT_DIR", tempDir.resolve("import").toString());

        AppConfig config = AppConfig.forTesting();
        DataPaths dataPaths = new DataPaths(config);
        dataPaths.initialize();

        TimeProvider timeProvider = new TimeProvider();
        CampaignService campaignService = new CampaignService(dataPaths, timeProvider);
        TurnService turnService = new TurnService(dataPaths, timeProvider);
        PlayerActionService playerActionService = new PlayerActionService(dataPaths, timeProvider);

        InteractionContext context = new InteractionContext();
        session = new InteractionSession(context, config, campaignService, turnService, playerActionService);

        manager = new InteractionManager();

        // Register commands
        manager.registerCommand(new HelpCommand(manager::getCommands));
        manager.registerCommand(new StatusCommand());
        manager.registerCommand(new ExitCommand(() -> {}));
    }

    @Test
    @DisplayName("应注册并使用 /help 命令")
    void shouldHandleHelpCommand() {
        InteractionResult result = manager.handle("/help", session);
        assertTrue(result.success());
        assertTrue(result.displayText().contains("GSimulator"));
        assertTrue(result.displayText().contains("help"));
        assertTrue(result.displayText().contains("status"));
        assertTrue(result.displayText().contains("exit"));
    }

    @Test
    @DisplayName("应注册并使用 /status 命令")
    void shouldHandleStatusCommand() {
        InteractionResult result = manager.handle("/status", session);
        assertTrue(result.success());
        assertTrue(result.displayText().contains("GSimulator 状态"));
        assertTrue(result.displayText().contains("ChromaDB"));
        assertTrue(result.displayText().contains("LLM"));
    }

    @Test
    @DisplayName("应注册并使用 /exit 命令")
    void shouldHandleExitCommand() {
        InteractionResult result = manager.handle("/exit", session);
        assertTrue(result.success());
        assertTrue(result.displayText().contains("再见"));
    }

    @Test
    @DisplayName("未知命令应返回错误")
    void shouldReturnErrorForUnknownCommand() {
        InteractionResult result = manager.handle("/unknown", session);
        assertFalse(result.success());
        assertTrue(result.displayText().contains("Unknown command"));
    }

    @Test
    @DisplayName("非命令输入应返回错误")
    void shouldReturnErrorForNonCommandInput() {
        InteractionResult result = manager.handle("hello world", session);
        assertFalse(result.success());
    }

    @Test
    @DisplayName("空输入应返回空结果")
    void shouldReturnEmptyForBlankInput() {
        InteractionResult result = manager.handle("", session);
        assertTrue(result.success());
        assertEquals("", result.displayText());
    }

    @Test
    @DisplayName("命令名大小写不敏感")
    void shouldBeCaseInsensitive() {
        InteractionResult result = manager.handle("/HELP", session);
        assertTrue(result.success());
        assertTrue(result.displayText().contains("GSimulator"));
    }

    @Test
    @DisplayName("已注册的命令应出现在命令列表中")
    void shouldListAllRegisteredCommands() {
        var commands = manager.getCommands();
        assertTrue(commands.containsKey("help"));
        assertTrue(commands.containsKey("status"));
        assertTrue(commands.containsKey("exit"));
    }
}
