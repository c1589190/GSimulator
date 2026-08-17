package com.gsim.core.doc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TMP 文档 GC（deleteByTypeOlderThan）与前缀去重列表（listByTypeAndPrefix）测试。 */
class DocStoreTmpGcTest {

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

    private Path fileOf(Document doc) {
        return docsDir.resolve(doc.type().key()).resolve(doc.id() + ".md");
    }

    private static void setMtime(Path file, Instant t) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(t));
    }

    @Test
    void onlyTmpDocsOlderThanCutoffAreDeleted() throws IOException {
        Document oldDoc = store.create("old1", DocType.TMP, "旧", "旧内容", List.of());
        Document freshDoc = store.create("fresh1", DocType.TMP, "新", "新内容", List.of());
        setMtime(fileOf(oldDoc), Instant.now().minus(10, ChronoUnit.DAYS));

        int removed = store.deleteByTypeOlderThan(DocType.TMP, Instant.now().minus(168, ChronoUnit.HOURS));

        assertEquals(1, removed);
        assertNull(store.get("old1"));
        assertFalse(Files.exists(fileOf(oldDoc)));
        assertNotNull(store.get("fresh1"));
        assertTrue(Files.exists(fileOf(freshDoc)));
    }

    @Test
    void listByTypeAndPrefixFiltersByDocIdPrefix() throws IOException {
        store.create("wstg_write_x", DocType.TMP, "写", "写内容", List.of());
        store.create("wstg_query_y", DocType.TMP, "查", "查内容", List.of());

        List<Document> queries = store.listByTypeAndPrefix(DocType.TMP, "wstg_query_");

        assertEquals(1, queries.size());
        assertEquals("wstg_query_y", queries.get(0).id());
    }

    @Test
    void nonTmpDocsOlderThanCutoffAreNotDeleted() throws IOException {
        Document charDoc = store.create("caocao", DocType.CHARACTER, "曹操", "角色", List.of());
        setMtime(fileOf(charDoc), Instant.now().minus(10, ChronoUnit.DAYS));

        int removed = store.deleteByTypeOlderThan(DocType.TMP, Instant.now().minus(168, ChronoUnit.HOURS));

        assertEquals(0, removed);
        assertNotNull(store.get("caocao"));
        assertTrue(Files.exists(fileOf(charDoc)));
    }

    @Test
    void tmpDocWithFutureMtimeIsNotDeleted() throws IOException {
        Document doc = store.create("future1", DocType.TMP, "未来", "内容", List.of());
        setMtime(fileOf(doc), Instant.now().plus(1, ChronoUnit.DAYS));

        int removed = store.deleteByTypeOlderThan(DocType.TMP, Instant.now().minus(168, ChronoUnit.HOURS));

        assertEquals(0, removed);
        assertNotNull(store.get("future1"));
    }
}
