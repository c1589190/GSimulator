package com.gsim.interaction.commands;

import com.gsim.app.AppConfig;
import com.gsim.campaign.CampaignService;
import com.gsim.campaign.PlayerActionService;
import com.gsim.campaign.TurnService;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionContext;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.player.PlayerProfileManager;
import com.gsim.storage.DataPaths;
import com.gsim.util.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PlayersCommand")
class PlayersCommandTest {

    @TempDir
    Path tempDir;

    private PlayersCommand cmd;
    private InteractionSession session;

    @BeforeEach
    void setUp() throws Exception {
        Path dataDir = tempDir.resolve("data");
        System.setProperty("GSIM_DATA_DIR", dataDir.toString());

        AppConfig config = AppConfig.forTesting();
        DataPaths dataPaths = new DataPaths(config);
        dataPaths.initialize();

        TimeProvider timeProvider = new TimeProvider();
        CampaignService campaignService = new CampaignService(dataPaths, timeProvider);
        TurnService turnService = new TurnService(dataPaths, timeProvider);
        PlayerActionService playerActionService = new PlayerActionService(dataPaths, timeProvider);

        InteractionContext context = new InteractionContext();
        session = new InteractionSession(context, config, campaignService, turnService, playerActionService);

        DataManager dm = new DataManager(dataDir);
        PlayerProfileManager pm = new PlayerProfileManager(dm);
        cmd = new PlayersCommand(pm);
    }

    @Test
    @DisplayName("命令名应为 players")
    void testCommandName() {
        assertEquals("players", cmd.name());
    }

    @Test
    @DisplayName("/players 应列出玩家")
    void shouldListPlayers() {
        InteractionResult result = cmd.execute(new String[0], session);
        assertTrue(result.success());
        assertTrue(result.displayText().contains("玩家"));
    }

    @Test
    @DisplayName("/players add 应创建玩家")
    void shouldAddPlayer() {
        InteractionResult result = cmd.execute(new String[]{"add", "测试玩家"}, session);
        assertTrue(result.success());
        assertTrue(result.displayText().contains("已创建玩家档案"));
        assertTrue(result.displayText().contains("测试玩家"));
    }

    @Test
    @DisplayName("/players set 应更新字段")
    void shouldSetField() {
        cmd.execute(new String[]{"add", "测试玩家"}, session);
        InteractionResult result = cmd.execute(new String[]{"set", "测试玩家", "阵营", "测试阵营"}, session);
        assertTrue(result.success());
        assertTrue(result.displayText().contains("阵营"));
    }

    @Test
    @DisplayName("/players show 应显示完整档案")
    void shouldShowProfile() {
        cmd.execute(new String[]{"add", "测试玩家"}, session);
        InteractionResult result = cmd.execute(new String[]{"show", "测试玩家"}, session);
        assertTrue(result.success());
        assertTrue(result.displayText().contains("测试玩家"));
        assertTrue(result.displayText().contains("类型"));
    }

    @Test
    @DisplayName("/players template 应打印模板")
    void shouldPrintTemplate() {
        InteractionResult result = cmd.execute(new String[]{"template"}, session);
        assertTrue(result.success());
        assertTrue(result.displayText().contains("类型"));
    }

    @Test
    @DisplayName("/players raw 应显示原文")
    void shouldShowRaw() {
        InteractionResult result = cmd.execute(new String[]{"raw"}, session);
        assertTrue(result.success());
        assertTrue(result.displayText().contains("玩家档案"));
    }

    @Test
    @DisplayName("未知子命令应返回错误")
    void shouldReturnErrorForUnknown() {
        InteractionResult result = cmd.execute(new String[]{"unknown"}, session);
        assertFalse(result.success());
    }
}
