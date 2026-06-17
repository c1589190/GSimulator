package com.gsim.importdata;

import com.gsim.app.AppConfig;
import com.gsim.chroma.ChromaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
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
     * 执行导入 — 扫描 import/ 目录并导入文件。
     */
    public ImportResult doImport() {
        if (!chromaClient.isAvailable()) {
            log.warn("ChromaDB is not available. Files saved to data/pending-imports/");
            return new ImportResult(0, 0, 0, List.of(), List.of(), "none",
                    "ChromaDB unavailable. Files saved to data/pending-imports/");
        }

        log.info("ImportManager.doImport() called (stub)");
        return new ImportResult(0, 0, 0, List.of(), List.of(), "world_lore",
                "Import not yet implemented (Phase 6)");
    }

    /**
     * 导入指定的文件列表（不扫描目录）。
     * 用于 web import 流程：先由 WebImportManager 抓取生成 txt，再由此方法入库。
     *
     * @param files 要导入的文件路径列表
     * @return 导入结果
     */
    public ImportResult importSpecificFiles(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return new ImportResult(0, 0, 0, List.of(), List.of(), "world_lore",
                    "No files to import.");
        }

        if (!chromaClient.isAvailable()) {
            log.warn("ChromaDB is not available. {} files saved to data/pending-imports/", files.size());
            return new ImportResult(files.size(), 0, files.size(),
                    List.of(), files.stream().map(Path::toString).toList(),
                    "none", "ChromaDB unavailable. Files saved to data/pending-imports/");
        }

        // Phase 6 stub — 暂时返回占位结果
        log.info("ImportManager.importSpecificFiles() called with {} files (stub)", files.size());
        var successFiles = files.stream().map(Path::toString).toList();
        return new ImportResult(files.size(), files.size(), 0,
                successFiles, List.of(), "world_lore",
                "Import not yet implemented (Phase 6). " + files.size() + " files queued.");
    }
}
