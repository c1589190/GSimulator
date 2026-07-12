package com.gsim.importdata;

import com.gsim.app.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 导入文件扫描器 — 扫描 import/ 目录，找出待导入的文件。
 */
public class ImportFileScanner {

    private final AppConfig config;

    public ImportFileScanner(AppConfig config) {
        this.config = config;
    }

    /**
     * 扫描待导入文件。
     */
    public List<Path> scan() throws IOException {
        Path importDir = config.getImportDir();
        if (!Files.exists(importDir)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(importDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedFile)
                    .toList();
        }
    }

    private boolean isSupportedFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".json");
    }
}
