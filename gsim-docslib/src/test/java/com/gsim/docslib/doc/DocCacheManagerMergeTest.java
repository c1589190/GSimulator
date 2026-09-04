package com.gsim.docslib.doc;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DocCacheManager @tmp:/@cache: 合并解析测试（T4）。
 *
 * <p>验证：@cache: 与 @tmp: 前缀读同一目录（docs/tmp/）；旧 .cache/*.txt 双读回退与迁移。
 */
class DocCacheManagerMergeTest {

    @TempDir
    Path tmpDir;

    private Path cacheDir;
    private DocCacheManager manager;

    @BeforeEach
    void setUp() throws Exception {
        cacheDir = tmpDir.resolve("tmp");
        Files.createDirectories(cacheDir);
        manager = new DocCacheManager(cacheDir);
        manager.init();
    }

    @Test
    void cachePrefixReadsFromCacheDir() throws Exception {
        String id = manager.put("doc", "缓存全文");
        assertEquals("缓存全文", manager.get(id));
        assertEquals("缓存全文", manager.resolve("@cache:" + id));
        assertEquals("缓存全文", manager.resolveDocId("@cache:" + id));
    }

    @Test
    void tmpPrefixReadsSameDirAsCache() throws Exception {
        String id = manager.put("doc", "tmp 全文");
        // @tmp: 与 @cache: 读同一目录（docs/tmp/）
        assertEquals("tmp 全文", manager.resolve("@tmp:" + id));
        assertEquals("tmp 全文", manager.resolveDocId("@tmp:" + id));
    }

    @Test
    void resolveEmbeddedRefsInText() throws Exception {
        String id = manager.put("doc", "内嵌内容");
        String text = "前置 @cache:" + id + " 后置";
        assertTrue(manager.resolve(text).contains("内嵌内容"));
        String text2 = "前置 @tmp:" + id + " 后置";
        assertTrue(manager.resolve(text2).contains("内嵌内容"));
    }

    @Test
    void missingCacheReturnsNullOrOriginal() {
        assertNull(manager.get("nonexistent"));
        assertEquals("@cache:nonexistent", manager.resolve("@cache:nonexistent"));
        assertNull(manager.resolveDocId("@cache:nonexistent"));
    }

    @Test
    void getFallsBackToLegacyCacheDir() throws Exception {
        // 旧目录：docs/.cache/（cacheDir 的父级 .cache）
        Path legacy = tmpDir.resolve(".cache");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("legacy1.txt"), "旧缓存");
        assertEquals("旧缓存", manager.get("legacy1"));
    }

    @Test
    void migrateFromLegacyMovesTxtFiles() throws Exception {
        Path legacy = tmpDir.resolve(".cache");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("old1.txt"), "旧1");
        Files.writeString(legacy.resolve("old2.txt"), "旧2");
        Files.writeString(legacy.resolve("skip.md"), "不迁移");

        int migrated = manager.migrateFromLegacy();
        assertEquals(2, migrated);
        assertTrue(Files.exists(cacheDir.resolve("old1.txt")));
        assertTrue(Files.exists(cacheDir.resolve("old2.txt")));
        assertFalse(Files.exists(cacheDir.resolve("skip.md")));
    }

    @Test
    void migrateIsIdempotent() throws Exception {
        Path legacy = tmpDir.resolve(".cache");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("old1.txt"), "旧1");
        manager.migrateFromLegacy();
        // 第二次迁移：目标已存在 → 跳过，返回 0
        assertEquals(0, manager.migrateFromLegacy());
    }
}
