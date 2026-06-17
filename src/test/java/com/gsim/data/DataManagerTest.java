package com.gsim.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataManager 测试 — 使用临时目录。
 */
@DisplayName("DataManager")
class DataManagerTest {

    @TempDir
    Path tempDir;

    private DataManager dm;

    @BeforeEach
    void setUp() throws Exception {
        dm = new DataManager(tempDir);
        dm.init();
    }

    @Test
    @DisplayName("init 应创建完整目录结构")
    void testInitCreatesStructure() {
        assertTrue(Files.isDirectory(tempDir.resolve("worlds/default/branches/main/always")));
        assertTrue(Files.isDirectory(tempDir.resolve("worlds/default/branches/main/entities")));
        assertTrue(Files.isDirectory(tempDir.resolve("worlds/default/branches/main/turns")));
        assertTrue(Files.isDirectory(tempDir.resolve("worlds/default/branches/main/patches/pending")));
        assertTrue(Files.isDirectory(tempDir.resolve("worlds/default/branches/main/patches/accepted")));
        assertTrue(Files.isDirectory(tempDir.resolve("worlds/default/branches/main/patches/rejected")));
    }

    @Test
    @DisplayName("init 后应能直接查询文档")
    void testListAfterInit() {
        var docs = dm.listAll();
        assertTrue(docs.size() >= 6); // 3 always + 2 entities + 1 turn + 1 branch = 7

        var entities = dm.listByType("entity");
        assertEquals(2, entities.size());
        assertTrue(entities.stream().anyMatch(d -> "entity.example-player".equals(d.id())));
        assertTrue(entities.stream().anyMatch(d -> "entity.example-character".equals(d.id())));
    }

    @Test
    @DisplayName("readById 应返回正确文档")
    void testReadById() {
        DataDocument doc = dm.readById("entity.example-player");
        assertNotNull(doc);
        assertEquals("entity", doc.type());
        assertEquals("player", doc.role());
        assertEquals("示例玩家", doc.name());
        assertTrue(doc.body().contains("玩家资料示例"));
    }

    @Test
    @DisplayName("listByType entity 应只返回 type=entity 的文档")
    void testListByType() {
        var entities = dm.listByType("entity");
        for (DataDocument doc : entities) {
            assertEquals("entity", doc.type());
        }
        assertTrue(entities.size() >= 2);
    }

    @Test
    @DisplayName("search 应按关键词搜索")
    void testSearch() {
        var results = dm.search("示例玩家", 5);
        assertFalse(results.isEmpty());
        DataSearchResult first = results.get(0);
        assertTrue(first.name().contains("示例玩家") || first.id().contains("example-player"));
        assertTrue(first.score() > 0);
    }

    @Test
    @DisplayName("search 无匹配应返回空")
    void testSearchNoMatch() {
        var results = dm.search("完全不存在的字符串xyz", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("getAlwaysContext 应包含 always 目录内容")
    void testGetAlwaysContext() {
        String ctx = dm.getAlwaysContext();
        assertTrue(ctx.contains("世界观"));
        assertTrue(ctx.contains("规则"));
        assertTrue(ctx.contains("当前状态"));
    }

    @Test
    @DisplayName("branch 创建和切换")
    void testBranchCreateAndSwitch() throws Exception {
        dm.createBranch("test", "main");
        assertTrue(dm.listBranches().contains("test"));

        dm.switchBranch("test");
        assertEquals("test", dm.getCurrentBranch());
        // 新分支应该有相同数量的文档（完整复制）
        assertTrue(dm.listAll().size() >= 6);

        // 切回 main
        dm.switchBranch("main");
        assertEquals("main", dm.getCurrentBranch());
    }

    @Test
    @DisplayName("parseFrontMatter 应正确解析")
    void testParseFrontMatter() {
        String input = """
                id: test.doc
                type: entity
                role: player
                name: 测试
                tags: [a, b]
                -------------------

                # 标题

                正文内容。""";

        DataManager.ParseResult result = DataManager.parseFrontMatter(input);
        assertEquals("test.doc", result.frontMatter().get("id"));
        assertEquals("entity", result.frontMatter().get("type"));
        assertEquals("player", result.frontMatter().get("role"));
        assertEquals("测试", result.frontMatter().get("name"));
        assertTrue(result.body().contains("正文内容"));
    }

    @Test
    @DisplayName("writeDoc 应写入并可通过 readById 读取")
    void testWriteAndRead() throws Exception {
        dm.writeDoc("entities/test.md",
                "id: entity.test\n" +
                "type: entity\n" +
                "role: npc\n" +
                "name: 测试NPC\n" +
                "tags: [npc]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 测试NPC\n\n一个新写入的角色。\n");

        dm.reload();
        DataDocument doc = dm.readById("entity.test");
        assertNotNull(doc);
        assertEquals("npc", doc.role());
        assertEquals("测试NPC", doc.name());
    }
}
