package com.gsim.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImportDocumentList — 拒绝指向外部的 symlink")
class ImportDocumentListRejectsSymlinkToOutsideTest {

    @TempDir Path tempDir;
    private Path importDir;
    private ImportDocumentService service;

    @BeforeEach
    void setUp() throws Exception {
        importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);

        // 正常文档
        Files.writeString(importDir.resolve("normal.txt"), "normal content");

        // symlink 指向 import 外部
        Path outsideFile = tempDir.resolve("outside.txt");
        Files.writeString(outsideFile, "sensitive data outside import");
        Path symlinkInside = importDir.resolve("evil-link.txt");
        Files.createSymbolicLink(symlinkInside, outsideFile);

        service = new ImportDocumentService(importDir);
    }

    @Test
    @DisplayName("listDocuments 不返回指向外部的 symlink")
    void listDocumentsSkipsSymlinkToOutside() throws Exception {
        List<ImportDocumentService.ImportDocumentInfo> docs = service.listDocuments();

        // 只应有 normal.txt
        assertEquals(1, docs.size(), "should only include normal.txt, not the external symlink");
        assertEquals("normal.txt", docs.get(0).displayName());
    }

    @Test
    @DisplayName("readDocument 拒绝读取指向外部的 symlink")
    void readDocumentRejectsSymlinkToOutside() {
        var ex = assertThrows(ImportDocumentService.ImportDocumentException.class,
                () -> service.readDocument("evil-link.txt", 0, 100, false));
        assertTrue(ex.getMessage().contains("Symlink target outside import dir")
                || ex.errorCode().equals("IMPORT_PATH_REJECTED"));
    }
}
