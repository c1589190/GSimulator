package com.gsim.root;

import com.gsim.data.DataManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证"明日方舟"输入生成结构化泰拉世界模板。
 */
class EmptyDataArknightsTextCreatesStructuredRootTest {

    @Test
    void arknightsTextCreatesStructuredRoot(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);

        // 验证 rootId 是 ASCII
        assertTrue(RootIdGenerator.isValidRootId(draft.rootIdSuggestion()));

        // 验证文件存在
        Path worldDir = dataRoot.resolve("worlds").resolve(draft.rootIdSuggestion());
        assertTrue(Files.exists(worldDir.resolve("world.md")));
        assertTrue(Files.exists(worldDir.resolve("entities.md")));
        assertTrue(Files.exists(worldDir.resolve("rules.md")));
        assertTrue(Files.exists(worldDir.resolve("players.md")));
        assertTrue(Files.exists(worldDir.resolve("branches").resolve("b0000-start.md")));

        // 验证 world.md 是结构化的（包含基本章节）
        String worldMd = Files.readString(worldDir.resolve("world.md"));
        assertTrue(worldMd.contains("世界观") || worldMd.contains("世界名称") || worldMd.contains("世界概述"),
                "world.md should be structured");

        // 验证 world.md 不只是原始用户文本
        assertFalse(worldMd.trim().equals("初始化一下世界观，就用明日方舟的"),
                "world.md should not be raw user text only");
    }

    @Test
    void bootstrapCreatesWorldEntitiesRulesPlayers(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);

        Path worldDir = dataRoot.resolve("worlds").resolve(draft.rootIdSuggestion());

        // entities.md 存在且非空
        String entitiesMd = Files.readString(worldDir.resolve("entities.md"));
        assertFalse(entitiesMd.isBlank(), "entities.md should not be empty");

        // rules.md 存在且非空
        String rulesMd = Files.readString(worldDir.resolve("rules.md"));
        assertFalse(rulesMd.isBlank(), "rules.md should not be empty");

        // players.md 存在且非空
        String playersMd = Files.readString(worldDir.resolve("players.md"));
        assertFalse(playersMd.isBlank(), "players.md should not be empty");

        // b0000-start.md 存在
        String branchMd = Files.readString(worldDir.resolve("branches").resolve("b0000-start.md"));
        assertTrue(branchMd.contains("branch.b0000-start") || branchMd.contains("时间原点"));
    }

    @Test
    void draftWarnsWorldNeedsVerification(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        // fallback 路径应该包含警告
        assertFalse(draft.warnings().isEmpty(), "Fallback draft should have warnings");
        boolean hasVerificationWarning = draft.warnings().stream()
                .anyMatch(w -> w.contains("待导入") || w.contains("核验") || w.contains("模板"));
        assertTrue(hasVerificationWarning, "Warnings should mention verification/import needed");

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        assertFalse(dm.needsRootBootstrap());
    }
}
