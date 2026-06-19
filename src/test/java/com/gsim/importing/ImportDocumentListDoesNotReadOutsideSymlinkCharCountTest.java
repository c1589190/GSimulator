package com.gsim.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImportDocumentList — 不读取外部 symlink 的 charCount")
class ImportDocumentListDoesNotReadOutsideSymlinkCharCountTest {

    @TempDir Path tempDir;
    private Path importDir;
    private ImportDocumentService service;
    private Path outsideFile;

    @BeforeEach
    void setUp() throws Exception {
        importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);

        // 正常文档
        Files.writeString(importDir.resolve("safe.txt"), "safe");

        // symlink 指向外部文件
        outsideFile = tempDir.resolve("secret.txt");
        Files.writeString(outsideFile, "THIS_IS_SECRET_DATA_THAT_SHOULD_NEVER_BE_READ");
        Path symlinkInside = importDir.resolve("spy-link.txt");
        Files.createSymbolicLink(symlinkInside, outsideFile);

        service = new ImportDocumentService(importDir);
    }

    @Test
    @DisplayName("listDocuments 中外部 symlink 的 charCount 不被读取")
    void charCountNotLeakedForOutsideSymlink() throws Exception {
        List<ImportDocumentService.ImportDocumentInfo> docs = service.listDocuments();

        // 外部 symlink 不应出现在列表中
        boolean hasSymlink = docs.stream()
                .anyMatch(d -> d.documentId().contains("spy-link"));
        assertFalse(hasSymlink, "symlink to outside must NOT appear in list");

        // safe.txt 正常列出
        assertTrue(docs.stream().anyMatch(d -> d.displayName().equals("safe.txt")));
    }

    @Test
    @DisplayName("readDocument 不泄露外部 symlink 文件内容")
    void contentNotLeakedForOutsideSymlink() {
        var ex = assertThrows(ImportDocumentService.ImportDocumentException.class,
                () -> service.readDocument("spy-link.txt", 0, 100, false));
        assertEquals("IMPORT_PATH_REJECTED", ex.errorCode());
    }
}
