package com.gsim.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImportDocumentSearch — 不写入 KnowledgeStore")
class ImportDocumentSearchDoesNotWriteKnowledgeStoreTest {

    @TempDir Path tempDir;
    private Path importDir;
    private ImportDocumentService service;

    @BeforeEach
    void setUp() throws Exception {
        importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);
        Files.writeString(importDir.resolve("setting.txt"), "老威廉的设定集：乌萨斯边境地区");
        service = new ImportDocumentService(importDir);
    }

    @Test
    @DisplayName("搜索 乌萨斯 返回匹配内容")
    void searchReturnsMatch() throws Exception {
        var matches = service.searchDocuments("乌萨斯", null, null, 10, 300, false);
        assertEquals(1, matches.size());
        assertTrue(matches.get(0).preview().contains("乌萨斯"));
        assertEquals("setting.txt", matches.get(0).displayName());
    }

    @Test
    @DisplayName("搜索不存在的关键词返回空列表")
    void searchNoMatchReturnsEmpty() throws Exception {
        var matches = service.searchDocuments("不存在", null, null, 10, 300, false);
        assertTrue(matches.isEmpty());
    }

    @Test
    @DisplayName("搜索限定单个 documentId")
    void searchWithDocumentId() throws Exception {
        Files.writeString(importDir.resolve("other.txt"), "其他文件");
        var matches = service.searchDocuments("设定", "setting.txt", null, 10, 300, false);
        assertEquals(1, matches.size());
        assertEquals("setting.txt", matches.get(0).displayName());
    }
}
