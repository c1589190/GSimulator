package com.gsim.core.doc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DocStore 嵌套 docId（a/b/c）创建与重启恢复测试。 */
class DocStoreNestedPathTest {

    @TempDir
    Path tempDir;

    private Path docsDir;
    private DocStore store;

    @BeforeEach
    void setUp() throws IOException {
        docsDir = tempDir.resolve("docs");
        store = new DocStore(docsDir);
        store.init();
    }

    @Test
    void nestedDocIdCreatesFileInTypeSubdirectoryAndReadsBack() throws IOException {
        Document doc = store.create("a/b/c", DocType.OTHER, "嵌套标题", "嵌套正文", List.of("tag1"));

        assertNotNull(doc);
        // create 的 docId 含 / 时文件落 docsDir/{type}/{a}/{b}/{c}.md
        Path file = docsDir.resolve("other").resolve("a").resolve("b").resolve("c.md");
        assertTrue(Files.isRegularFile(file));

        Document read = store.get("a/b/c");
        assertNotNull(read);
        assertEquals("嵌套标题", read.title());
        assertEquals("嵌套正文", read.content());
        assertEquals(DocType.OTHER, read.type());
    }

    @Test
    void nestedDocSurvivesRestartViaRecursiveScan() throws IOException {
        store.create("a/b/c", DocType.OTHER, "嵌套标题", "嵌套正文", List.of("tag1"));

        // 模拟重启：新 DocStore 实例 init() 递归扫描 docsDir
        DocStore restarted = new DocStore(docsDir);
        restarted.init();

        // init() 剥离 {type}/ 布局段，重启后 docId 恢复为 a/b/c
        Document doc = restarted.get("a/b/c");
        assertNotNull(doc);
        assertEquals("嵌套标题", doc.title());
        assertEquals(DocType.OTHER, doc.type());
        // toFileContent() 末尾追加 \n，重启后 content 含结尾换行
        assertEquals("嵌套正文\n", doc.content());
    }

    @Test
    void topLevelDocRestartsWithoutTypePrefix() throws IOException {
        store.create("abc", DocType.OTHER, "顶层", "顶层正文", List.of());

        // 模拟重启：docs/other/abc.md → docId 恢复为 "abc"（回归修复）
        DocStore restarted = new DocStore(docsDir);
        restarted.init();

        assertNotNull(restarted.get("abc"));
        assertNull(restarted.get("other/abc"));
    }
}
