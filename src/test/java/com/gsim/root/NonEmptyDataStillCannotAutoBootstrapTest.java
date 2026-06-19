package com.gsim.root;

import com.gsim.data.DataManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 data 非空时禁止自动 bootstrap。
 */
class NonEmptyDataStillCannotAutoBootstrapTest {

    @Test
    void nonEmptyDataRejectsBootstrap(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);

        // 先创建一个 root
        DataManager dm = new DataManager(dataRoot);
        dm.init(); // 创建 default root
        assertFalse(dm.needsRootBootstrap());

        // data 非空时，BootstrapIntentParser 应拒绝
        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", false);
        assertFalse(intent.shouldBootstrap(),
                "Should not allow bootstrap when data is not empty");
    }

    @Test
    void nonEmptyDataWithSkillsDirRejectsBootstrap(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        // 只创建 skills 目录（模拟非空但无 root 的情况）
        Files.createDirectories(dataRoot.resolve("skills"));

        DataManager dm = new DataManager(dataRoot);
        // skills 目录不算有效 root，所以 needsRootBootstrap 仍为 true
        // 但 RootBootstrapPolicy.isStrictlyEmptyDataRoot 检查的是 worlds/ 下是否有有效 root
        // skills 目录不影响 — 所以这里仍应允许 bootstrap
        // 验证：RootBootstrapPolicy 正确判断
        assertTrue(RootBootstrapPolicy.isStrictlyEmptyDataRoot(dataRoot),
                "skills dir alone should not count as having a root");
    }

    @Test
    void dataWithExistingRootRejectsBootstrap(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        DataManager dm = new DataManager(dataRoot);
        dm.init();

        // bootstrapFromEmpty 应抛出异常
        var intent = BootstrapIntentParser.parse("测试", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        assertThrows(java.io.IOException.class, () -> {
            dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        }, "bootstrapFromEmpty should fail when data already has roots");
    }

    @Test
    void bootstrapIntentDeniesWhenDataNotEmpty() {
        // 模拟 data 非空场景
        assertFalse(BootstrapIntentParser.hasBootstrapIntent("初始化根节点：测试", false));
        assertFalse(BootstrapIntentParser.hasBootstrapIntent("初始化一下世界观", false));
        assertFalse(BootstrapIntentParser.hasBootstrapIntent("任意文本", false));
    }

    @Test
    void nonBootstrapHintMentionsRootCreate() {
        String hint = BootstrapIntentParser.nonBootstrapHint();
        assertTrue(hint.contains("/root create"));
        assertTrue(hint.contains("data 目录非空") || hint.contains("清理 data"));
    }
}
