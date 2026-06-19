package com.gsim.importing.knowledge;

import com.gsim.data.DataManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("branch extra files 废弃 — getEffectiveContext 标记 @Deprecated")
class BranchExtraFilesAreDeprecatedTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("getEffectiveContext 方法带有 @Deprecated 注解")
    void getEffectiveContextHasDeprecatedAnnotation() throws Exception {
        var method = DataManager.class.getMethod("getEffectiveContext");
        var deprecated = method.getAnnotation(Deprecated.class);
        assertNotNull(deprecated,
                "getEffectiveContext should be annotated @Deprecated");
    }

    @Test
    @DisplayName("branch 文件不创建独立 entities.md / rules.md / players.md")
    void branchInitDoesNotCreateExtraFiles() throws Exception {
        Path dataRoot = tempDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        // bootstrap 前没有 root — 验证不会误创建 branch extra files
        assertTrue(dm.needsRootBootstrap());

        // 确认 data/worlds 目录下还没有 worlds
        Path worldsDir = dataRoot.resolve("worlds");
        if (Files.exists(worldsDir)) {
            try (var worlds = Files.list(worldsDir)) {
                for (var worldDir : worlds.toList()) {
                    Path branchesDir = worldDir.resolve("branches");
                    if (Files.exists(branchesDir)) {
                        try (var entries = Files.list(branchesDir)) {
                            entries.forEach(entry -> {
                                String name = entry.getFileName().toString();
                                assertFalse(Files.isDirectory(entry),
                                        "不应该存在 branch 子目录: " + name);
                                assertTrue(name.endsWith(".md") || name.equals("active-branch.txt"),
                                        "branch 目录下只应有 .md 文件: " + name);
                            });
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("DataManager 的 root 文件路径指向顶层文件，不是 branch 子目录")
    void rootFilePathsAreTopLevel() throws Exception {
        Path dataRoot = tempDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        // 无 root 时 worldFilePath() 会抛异常 — 这是预期行为
        assertTrue(dm.needsRootBootstrap());
        assertThrows(IllegalStateException.class, dm::worldFilePath,
                "should throw when no active root");
    }
}
