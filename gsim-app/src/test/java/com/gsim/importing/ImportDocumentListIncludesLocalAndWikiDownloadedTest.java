package com.gsim.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImportDocumentList — 包含 LOCAL_IMPORT 和 WIKI_DOWNLOADED")
class ImportDocumentListIncludesLocalAndWikiDownloadedTest {

    @TempDir Path tempDir;
    private Path importDir;
    private ImportDocumentService service;

    @BeforeEach
    void setUp() throws Exception {
        importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);

        // 本地文档
        Files.writeString(importDir.resolve("老威廉设定集.txt"), "设定内容");

        // wiki 下载文档
        Path wikiDir = importDir.resolve("web").resolve("prts.wiki");
        Files.createDirectories(wikiDir);
        Files.writeString(wikiDir.resolve("乌萨斯-page.txt"), "wiki内容");

        service = new ImportDocumentService(importDir);
    }

    @Test
    @DisplayName("listDocuments 返回 LOCAL_IMPORT 和 WIKI_DOWNLOADED 两类文档")
    void listIncludesBothSources() throws Exception {
        List<ImportDocumentService.ImportDocumentInfo> docs = service.listDocuments();
        assertEquals(2, docs.size());

        var local = docs.stream().filter(d -> d.source().equals("LOCAL_IMPORT")).findFirst();
        var wiki = docs.stream().filter(d -> d.source().equals("WIKI_DOWNLOADED")).findFirst();

        assertTrue(local.isPresent(), "should have LOCAL_IMPORT doc");
        assertTrue(wiki.isPresent(), "should have WIKI_DOWNLOADED doc");
        assertEquals("老威廉设定集.txt", local.get().displayName());
        assertEquals("乌萨斯-page.txt", wiki.get().displayName());
    }

    @Test
    @DisplayName("import 目录为空时返回空列表")
    void emptyImportDirReturnsEmptyList() throws Exception {
        Path emptyDir = tempDir.resolve("import-empty");
        Files.createDirectories(emptyDir);
        var emptyService = new ImportDocumentService(emptyDir);
        assertTrue(emptyService.listDocuments().isEmpty());
    }

    @Test
    @DisplayName("import 目录不存在时返回空列表")
    void missingImportDirReturnsEmptyList() throws Exception {
        var missingService = new ImportDocumentService(tempDir.resolve("nonexistent"));
        assertTrue(missingService.listDocuments().isEmpty());
    }
}
