package com.gsim.player;

import com.gsim.data.DataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PlayerProfileManager")
class PlayerProfileManagerTest {

    @TempDir
    Path tempDir;

    private DataManager dm;
    private PlayerProfileManager pm;

    @BeforeEach
    void setUp() throws Exception {
        Path dataDir = tempDir.resolve("data");
        dm = new DataManager(dataDir);
        pm = new PlayerProfileManager(dm);
    }

    @Test
    @DisplayName("初始 world 应存在 players.md")
    void shouldHavePlayersFileAfterInit() {
        assertTrue(Files.exists(dm.getPlayersPath()));
        String content = dm.readPlayers();
        assertTrue(content.contains("玩家档案"));
    }

    @Test
    @DisplayName("addPlayer 应创建新玩家档案")
    void shouldAddPlayer() {
        PlayerProfile profile = PlayerProfile.createTemplate("测试玩家");
        pm.addPlayer(profile);

        assertTrue(pm.exists("测试玩家"));
        List<PlayerProfile> list = pm.listPlayers();
        assertEquals(2, list.size()); // 模板示例玩家 + 新加玩家
        assertEquals("测试玩家", list.get(1).name());
    }

    @Test
    @DisplayName("updatePlayerField 应更新已有字段")
    void shouldUpdateField() {
        pm.addPlayer(PlayerProfile.createTemplate("测试玩家"));
        pm.updatePlayerField("测试玩家", "faction", "测试阵营");

        Optional<PlayerProfile> opt = pm.getPlayer("测试玩家");
        assertTrue(opt.isPresent());
        assertEquals("测试阵营", opt.get().faction());
    }

    @Test
    @DisplayName("updatePlayerField 对不存在的玩家应自动创建")
    void shouldAutoCreateOnUpdate() {
        pm.updatePlayerField("新玩家", "identity", "新身份");

        assertTrue(pm.exists("新玩家"));
        Optional<PlayerProfile> opt = pm.getPlayer("新玩家");
        assertTrue(opt.isPresent());
        assertEquals("新身份", opt.get().identity());
        assertEquals("未设定", opt.get().faction()); // 其他字段默认未设定
    }

    @Test
    @DisplayName("appendPlayerNote 应追加备注")
    void shouldAppendNote() {
        pm.addPlayer(PlayerProfile.createTemplate("测试玩家"));
        pm.appendPlayerNote("测试玩家", "第一条备注");
        pm.appendPlayerNote("测试玩家", "第二条备注");

        Optional<PlayerProfile> opt = pm.getPlayer("测试玩家");
        assertTrue(opt.isPresent());
        assertTrue(opt.get().notes().contains("第一条备注"));
        assertTrue(opt.get().notes().contains("第二条备注"));
    }

    @Test
    @DisplayName("removePlayer 应删除玩家")
    void shouldRemovePlayer() {
        pm.addPlayer(PlayerProfile.createTemplate("测试玩家"));
        assertTrue(pm.exists("测试玩家"));

        pm.removePlayer("测试玩家");
        assertFalse(pm.exists("测试玩家"));
    }

    @Test
    @DisplayName("listPlayers 应包含模板示例玩家")
    void shouldIncludeTemplatePlayer() {
        List<PlayerProfile> list = pm.listPlayers();
        assertFalse(list.isEmpty(), "初始模板应包含示例玩家");
    }
}
