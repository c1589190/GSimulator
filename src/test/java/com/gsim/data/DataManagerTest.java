package com.gsim.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DataManager")
class DataManagerTest {

    @TempDir
    Path tempDir;

    private DataManager dm;

    @BeforeEach
    void setUp() {
        // DataManager 构造时自动创建 default world
        dm = new DataManager(tempDir);
    }

    @Test
    @DisplayName("构造时应自动创建 default world")
    void testAutoInit() {
        assertTrue(Files.isDirectory(tempDir.resolve("worlds/default/branches/main/always")));
        assertTrue(Files.isDirectory(tempDir.resolve("worlds/default/branches/main/entities")));
        assertTrue(Files.exists(tempDir.resolve("active-world.txt")));
    }

    @Test
    @DisplayName("自动加载后应能直接查询")
    void testAutoLoad() {
        assertEquals("default", dm.getCurrentWorld());
        assertEquals("main", dm.getCurrentBranch());
        assertTrue(dm.docCount() >= 6);
    }

    @Test
    @DisplayName("应能列出所有 world")
    void testListWorlds() {
        var worlds = dm.listWorlds();
        assertTrue(worlds.contains("default"));
    }

    @Test
    @DisplayName("应能创建和切换 world")
    void testCreateAndSwitchWorld() throws Exception {
        dm.createWorld("testworld");
        assertTrue(dm.listWorlds().contains("testworld"));

        dm.switchWorld("testworld");
        assertEquals("testworld", dm.getCurrentWorld());
        assertEquals("main", dm.getCurrentBranch());
        assertTrue(dm.docCount() >= 6);
    }

    @Test
    @DisplayName("切换 world 后原 world 不受影响")
    void testWorldIsolation() throws Exception {
        dm.createWorld("testworld");
        dm.switchWorld("testworld");
        dm.writeDoc("entities/only-in-test.md",
                "id: entity.only-in-test\ntype: entity\nname: OnlyTest\ntags: []\nupdated: 2026-06-18\n-------------------\n\n# Only in test\n");
        dm.reload();
        assertNotNull(dm.readById("entity.only-in-test"));

        dm.switchWorld("default");
        assertNull(dm.readById("entity.only-in-test"));
    }

    @Test
    @DisplayName("branch 创建和切换")
    void testBranchCreateAndSwitch() throws Exception {
        dm.createBranch("alt", "main");
        assertTrue(dm.listBranches().contains("alt"));
        dm.switchBranch("alt");
        assertEquals("alt", dm.getCurrentBranch());
    }

    @Test
    @DisplayName("搜索")
    void testSearch() {
        var r = dm.search("示例玩家", 5);
        assertFalse(r.isEmpty());
    }

    @Test
    @DisplayName("getAlwaysContext")
    void testGetAlwaysContext() {
        String ctx = dm.getAlwaysContext();
        assertTrue(ctx.contains("世界观"));
    }

    @Test
    @DisplayName("init 显式调用应可用")
    void testExplicitInit() throws Exception {
        dm.init();
        assertTrue(dm.docCount() >= 6);
    }

    @Test
    @DisplayName("已有 data 时构造应正确加载")
    void testReloadAfterExisting() {
        // 第二个 DataManager 应从已有 data 加载
        DataManager dm2 = new DataManager(tempDir);
        assertEquals("default", dm2.getCurrentWorld());
        assertEquals("main", dm2.getCurrentBranch());
        assertTrue(dm2.docCount() >= 6);
    }
}
