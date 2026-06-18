package com.gsim.root;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RootBootstrapPolicyTest {

    @Test
    void nullDataRootIsEmpty() {
        assertTrue(RootBootstrapPolicy.isStrictlyEmptyDataRoot(null));
    }

    @Test
    void nonExistentDirectoryIsEmpty() {
        assertTrue(RootBootstrapPolicy.isStrictlyEmptyDataRoot(Path.of("/nonexistent/path/for/test")));
    }

    @Test
    void emptyDirectoryIsEmpty(@TempDir Path tmpDir) {
        assertTrue(RootBootstrapPolicy.isStrictlyEmptyDataRoot(tmpDir));
    }

    @Test
    void directoryWithWorldsDirButNoValidRootIsEmpty(@TempDir Path tmpDir) throws Exception {
        Files.createDirectories(tmpDir.resolve("worlds"));
        assertTrue(RootBootstrapPolicy.isStrictlyEmptyDataRoot(tmpDir));
    }

    @Test
    void directoryWithValidRootIsNotEmpty(@TempDir Path tmpDir) throws Exception {
        Path worldDir = tmpDir.resolve("worlds").resolve("test-root");
        Files.createDirectories(worldDir);
        Files.writeString(worldDir.resolve("world.md"), "test");
        assertFalse(RootBootstrapPolicy.isStrictlyEmptyDataRoot(tmpDir));
        assertTrue(RootBootstrapPolicy.hasAnyRoot(tmpDir));
    }

    @Test
    void directoryWithB0000StartIsNotEmpty(@TempDir Path tmpDir) throws Exception {
        Path worldDir = tmpDir.resolve("worlds").resolve("test-root");
        Files.createDirectories(worldDir.resolve("branches"));
        Files.writeString(worldDir.resolve("branches").resolve("b0000-start.md"), "test");
        assertTrue(RootBootstrapPolicy.hasAnyRoot(tmpDir));
    }

    @Test
    void listRootIdsReturnsSorted(@TempDir Path tmpDir) throws Exception {
        for (String id : new String[]{"ccc", "aaa", "bbb"}) {
            Path wd = tmpDir.resolve("worlds").resolve(id);
            Files.createDirectories(wd);
            Files.writeString(wd.resolve("world.md"), "test");
        }
        var ids = RootBootstrapPolicy.listRootIds(tmpDir);
        assertEquals(3, ids.size());
        assertEquals("aaa", ids.get(0));
        assertEquals("bbb", ids.get(1));
        assertEquals("ccc", ids.get(2));
    }

    @Test
    void isValidRootWithWorldMd(@TempDir Path tmpDir) throws Exception {
        Files.writeString(tmpDir.resolve("world.md"), "content");
        assertTrue(RootBootstrapPolicy.isValidRoot(tmpDir));
    }

    @Test
    void isValidRootWithBranchFile(@TempDir Path tmpDir) throws Exception {
        Files.createDirectories(tmpDir.resolve("branches"));
        Files.writeString(tmpDir.resolve("branches").resolve("b0000-start.md"), "content");
        assertTrue(RootBootstrapPolicy.isValidRoot(tmpDir));
    }

    @Test
    void knowledgeDbPath(@TempDir Path tmpDir) {
        Path db = RootBootstrapPolicy.knowledgeDbPath(tmpDir, "my-root");
        assertTrue(db.toString().contains("worlds/my-root/knowledge/gsim.db"));
    }
}
