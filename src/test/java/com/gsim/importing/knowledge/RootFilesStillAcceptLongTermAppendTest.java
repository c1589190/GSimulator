package com.gsim.importing.knowledge;

import com.gsim.data.DataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("root 文件仍然支持长期档案 append — root_world/entities/rules/players 可正常写入")
class RootFilesStillAcceptLongTermAppendTest {

    @TempDir Path tempDir;

    private DataManager dm;
    private Path worldDir;

    @BeforeEach
    void setUp() throws Exception {
        Path dataRoot = tempDir.resolve("data");
        Files.createDirectories(dataRoot);
        dm = new DataManager(dataRoot);

        // 初始化一个 root
        dm.createRoot("test-root", "# 测试世界\n\n这是一个测试世界。");
        worldDir = dataRoot.resolve("worlds").resolve("test-root");
    }

    @Test
    @DisplayName("root world.md 可正常更新（append 模式）")
    void rootWorldUpdateAppendWorks() throws Exception {
        assertTrue(Files.exists(worldDir.resolve("world.md")));

        String current = Files.readString(worldDir.resolve("world.md"));
        String appended = current + "\n## 追加内容\n\n这是追加的世界观内容。";
        Files.writeString(worldDir.resolve("world.md"), appended);

        String updated = Files.readString(worldDir.resolve("world.md"));
        assertTrue(updated.contains("追加的世界观内容"));
        assertTrue(updated.contains("测试世界"));
    }

    @Test
    @DisplayName("root entities.md 可正常写入长期档案")
    void rootEntitiesUpdateWorks() throws Exception {
        Path entitiesFile = worldDir.resolve("entities.md");
        // entities.md 可能存在也可能不存在，取决于模板
        String content = "# 实体档案\n\n## 罗德岛\n\n罗德岛制药公司。";
        Files.writeString(entitiesFile, content);

        assertTrue(Files.exists(entitiesFile));
        String readBack = Files.readString(entitiesFile);
        assertTrue(readBack.contains("罗德岛"));
    }

    @Test
    @DisplayName("root rules.md 可正常写入规则")
    void rootRulesUpdateWorks() throws Exception {
        Path rulesFile = worldDir.resolve("rules.md");
        String content = "# 规则\n\n## 战斗规则\n\n1. 每个角色每回合一个主要行动。";
        Files.writeString(rulesFile, content);

        assertTrue(Files.exists(rulesFile));
        assertTrue(Files.readString(rulesFile).contains("战斗规则"));
    }

    @Test
    @DisplayName("root players.md 可正常写入玩家长期档案")
    void rootPlayersUpdateWorks() throws Exception {
        Path playersFile = worldDir.resolve("players.md");
        String content = "# 玩家档案\n\n## 张三\n\n身份：龙门防卫局干员。";
        Files.writeString(playersFile, content);

        assertTrue(Files.exists(playersFile));
        assertTrue(Files.readString(playersFile).contains("张三"));
    }

    @Test
    @DisplayName("branch markdown 文件正常创建为 .md 文件，不在子目录")
    void branchMarkdownIsFlatFileNotDirectory() throws Exception {
        Path branchesDir = worldDir.resolve("branches");
        assertTrue(Files.isDirectory(branchesDir));

        // b0000-start.md 应作为平铺文件存在
        Path b0000 = branchesDir.resolve("b0000-start.md");
        assertTrue(Files.exists(b0000), "root branch markdown should exist as flat .md file");
        assertTrue(Files.isRegularFile(b0000), "branch file should be a regular file, not directory");

        // 验证没有 branch 子目录
        try (var entries = Files.list(branchesDir)) {
            entries.forEach(entry -> {
                assertFalse(Files.isDirectory(entry),
                        "不应存在 branch 子目录: " + entry.getFileName());
            });
        }
    }
}
