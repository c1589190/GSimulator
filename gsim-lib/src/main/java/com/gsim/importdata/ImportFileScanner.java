package com.gsim.importdata;

import com.gsim.app.AppConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 导入文件扫描器 — 扫描 import/ 目录，找出待导入的文件。
 */
public class ImportFileScanner {

    private final AppConfig config;

    /**
     * 创建导入文件扫描器。
     *
     * @param config 应用配置，用于获取导入目录路径
     */
    public ImportFileScanner(AppConfig config) {
        this.config = config;
    }

    /**
     * 扫描待导入文件。
     *
     * @return 待导入文件的路径列表（可能为空）
     * @throws IOException 如果读取导入目录时发生 I/O 错误
     */
    public List<Path> scan() throws IOException {
        Path importDir = config.getImportDir();
        if (!Files.exists(importDir)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(importDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(this::isSupportedFile)
                    .toList();
        }
    }

    private boolean isSupportedFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".json");
    }
}
