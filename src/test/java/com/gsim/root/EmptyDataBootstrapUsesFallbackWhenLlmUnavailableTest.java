package com.gsim.root;

import com.gsim.data.DataManager;
import com.gsim.llm.FakeLlmManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 LLM 不可用时使用 deterministic fallback。
 */
class EmptyDataBootstrapUsesFallbackWhenLlmUnavailableTest {

    @Test
    void nullLlmClientUsesFallback(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", true);
        // null LlmClient → fallback
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        assertNotNull(draft);
        assertFalse(draft.worldMarkdown().isBlank());
        assertTrue(draft.worldMarkdown().contains("世界名称") || draft.worldMarkdown().contains("世界观"),
                "Fallback world.md should contain basic structure");

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        assertFalse(dm.needsRootBootstrap());
    }

    @Test
    void unavailableLlmClientUsesFallback(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        FakeLlmManager fakeLlm = new FakeLlmManager();
        fakeLlm.setAvailable(false); // LLM 不可用

        var intent = BootstrapIntentParser.parse("帮我建一个罗马尼亚1876开局", true);
        var generator = new BootstrapWorldDraftGenerator(fakeLlm, "test-model");
        var draft = generator.generate(intent);

        assertNotNull(draft);
        assertFalse(draft.worldMarkdown().isBlank());
        // fallback 应包含警告
        assertFalse(draft.warnings().isEmpty());

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        assertFalse(dm.needsRootBootstrap());
    }

    @Test
    void fallbackWorldMdHasRequiredSections(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse("开个泰拉世界", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        String worldMd = draft.worldMarkdown();
        assertTrue(worldMd.contains("世界名称"), "Fallback world.md should have 世界名称");
        assertTrue(worldMd.contains("资料状态") || worldMd.contains("待导入") || worldMd.contains("待补全"),
                "Fallback world.md should mention data status");

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        assertFalse(dm.needsRootBootstrap());
    }

    @Test
    void fallbackEntitiesMdIsNotEmpty(@TempDir Path tmpDir) throws Exception {
        var intent = BootstrapIntentParser.parse("测试世界", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        assertFalse(draft.entitiesMarkdown().isBlank());
        assertTrue(draft.entitiesMarkdown().contains("实体") || draft.entitiesMarkdown().contains("势力")
                || draft.entitiesMarkdown().contains("待补全"));
    }

    @Test
    void fallbackRulesMdIsNotEmpty(@TempDir Path tmpDir) throws Exception {
        var intent = BootstrapIntentParser.parse("测试世界", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        assertFalse(draft.rulesMarkdown().isBlank());
        assertTrue(draft.rulesMarkdown().contains("规则") || draft.rulesMarkdown().contains("推演"));
    }
}
