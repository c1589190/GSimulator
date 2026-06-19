package com.gsim.root;

import com.gsim.data.DataManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 data 为空时任意非空自然语言输入都能触发 bootstrap。
 */
class EmptyDataAnyTextBootstrapsRootTest {

    @Test
    void anyTextBootstrapsRootWhenDataEmpty(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);
        assertTrue(dm.needsRootBootstrap());

        // 使用 BootstrapWorldDraftGenerator 的 fallback 路径（无 LLM）
        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", true);
        assertTrue(intent.shouldBootstrap());

        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        assertNotNull(draft.rootIdSuggestion());
        assertTrue(RootIdGenerator.isValidRootId(draft.rootIdSuggestion()));
        assertNotNull(draft.title());
        assertFalse(draft.worldMarkdown().isBlank());
        assertFalse(draft.entitiesMarkdown().isBlank());
        assertFalse(draft.rulesMarkdown().isBlank());
        assertFalse(draft.playersMarkdown().isBlank());
        assertFalse(draft.rootBranchInput().isBlank());

        // Bootstrap
        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        assertFalse(dm.needsRootBootstrap());
        assertEquals(draft.rootIdSuggestion(), dm.getActiveRootId());
    }

    @Test
    void shortTextBootstrapsRoot(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse("开个泰拉世界", true);
        assertTrue(intent.shouldBootstrap());

        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        assertFalse(dm.needsRootBootstrap());
    }

    @Test
    void longTextBootstrapsRoot(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse(
                "我要做一个1850年代架空东南亚的文游，有殖民势力、本地王国和华人商帮", true);
        assertTrue(intent.shouldBootstrap());

        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        assertFalse(dm.needsRootBootstrap());
    }
}
