package com.gsim.importdata;

import com.gsim.app.AppConfig;
import com.gsim.chroma.ChromaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 导入管理器 — 协调整个 import 管道。
 * Phase 6 之前返回占位实现。
 */
public class ImportManager {

    private static final Logger log = LoggerFactory.getLogger(ImportManager.class);

    private final AppConfig config;
    private final ChromaClient chromaClient;
    private final ImportFileScanner scanner;
    private final ImportClassifier classifier;
    private final TextChunker chunker;

    public ImportManager(AppConfig config, ChromaClient chromaClient) {
        this.config = config;
        this.chromaClient = chromaClient;
        this.scanner = new ImportFileScanner(config);
        this.classifier = new ImportClassifier();
        this.chunker = new TextChunker();
    }

    /**
     * 执行导入。
     * Phase 6 之前返回占位结果。
     */
    public ImportResult doImport() {
        if (!chromaClient.isAvailable()) {
            log.warn("ChromaDB is not available. Use data/pending-imports/ for now.");
            // TODO Phase 6: 保存到 pending-imports
            return new ImportResult(0, 0, 0, List.of(), List.of(), "none",
                    "ChromaDB unavailable. Files saved to data/pending-imports/");
        }

        // TODO Phase 6: 完整实现 import 管道
        log.info("ImportManager.doImport() called (stub)");
        return new ImportResult(0, 0, 0, List.of(), List.of(), "world_lore",
                "Import not yet implemented (Phase 6)");
    }
}
