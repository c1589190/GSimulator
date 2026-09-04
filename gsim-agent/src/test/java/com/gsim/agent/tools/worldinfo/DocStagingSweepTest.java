package com.gsim.core.tools.worldinfo;

import com.gsim.docslib.staging.DocStaging;
import static org.junit.jupiter.api.Assertions.*;

import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.doc.DocType;
import com.gsim.docslib.doc.Document;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DocStaging 清扫（cleanupEnabled）与同前缀去重测试。 */
class DocStagingSweepTest {

    @TempDir
    Path tmpDir;

    private Path docsDir;
    private DocStore docStore;

    @BeforeEach
    void setUp() throws IOException {
        docsDir = tmpDir.resolve("docs");
        docStore = new DocStore(docsDir);
        docStore.init();
    }

    private Path fileOf(Document doc) {
        return docsDir.resolve(doc.type().key()).resolve(doc.id() + ".md");
    }

    private static void setMtime(Path file, Instant t) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(t));
    }

    @Test
    void staleTmpDocIsSweptBeforeNewStageWhenCleanupEnabled() throws IOException {
        Document stale = docStore.create("wstg_write_stale", DocType.TMP, "旧", "旧内容", List.of());
        setMtime(fileOf(stale), Instant.now().minus(10, ChronoUnit.DAYS));

        String docId =
                DocStaging.stage(docStore, "wstg_write_", "n0000:worldview:大事件", "新内容", true, Duration.ofHours(168));

        assertNull(docStore.get("wstg_write_stale"));
        assertFalse(Files.exists(fileOf(stale)));
        Document staged = docStore.get(docId);
        assertNotNull(staged);
        assertEquals(DocType.TMP, staged.type());
        assertEquals("新内容", staged.content());
    }

    @Test
    void staleTmpDocSurvivesWhenCleanupDisabled() throws IOException {
        Document stale = docStore.create("wstg_write_stale", DocType.TMP, "旧", "旧内容", List.of());
        setMtime(fileOf(stale), Instant.now().minus(10, ChronoUnit.DAYS));

        String docId = DocStaging.stage(docStore, "wstg_write_", "n0000:worldview:大事件", "新内容");

        assertNotNull(docStore.get("wstg_write_stale"));
        assertNotNull(docStore.get(docId));
    }

    @Test
    void dedupStillHitsSamePrefixContentWithCleanupEnabled() throws IOException {
        String content = "重复内容";
        String docId1 =
                DocStaging.stage(docStore, "wstg_write_", "n0000:worldview:大事件", content, true, Duration.ofHours(168));
        String docId2 =
                DocStaging.stage(docStore, "wstg_write_", "n0000:worldview:大事件", content, true, Duration.ofHours(168));

        assertEquals(docId1, docId2);
        assertEquals(1, docStore.listByTypeAndPrefix(DocType.TMP, "wstg_write_").size());
    }
}
