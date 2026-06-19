package com.gsim.root;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 rootId 始终是 ASCII-only。
 */
class EmptyDataBootstrapRootIdAsciiOnlyTest {

    @Test
    void rootIdContainsNoChinese() {
        String id = RootIdGenerator.suggestRootId("初始化一下世界观，就用明日方舟的");
        assertTrue(RootIdGenerator.isValidRootId(id));
        for (char c : id.toCharArray()) {
            assertFalse(Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                    "rootId should not contain Chinese characters: " + id);
        }
    }

    @Test
    void rootIdContainsNoSpaces() {
        String id = RootIdGenerator.suggestRootId("test with spaces");
        assertFalse(id.contains(" "), "rootId should not contain spaces: " + id);
    }

    @Test
    void rootIdContainsNoAnsiControlChars() {
        String id = RootIdGenerator.suggestRootId("normal text");
        for (char c : id.toCharArray()) {
            assertFalse(c < 0x20 && c != '\n' && c != '\r' && c != '\t',
                    "rootId should not contain control chars: " + id);
        }
    }

    @Test
    void rootIdMatchesAllowedPattern() {
        String id = RootIdGenerator.suggestRootId("some random text");
        assertTrue(id.matches("[a-zA-Z0-9._-]+"), "rootId should match [a-zA-Z0-9._-]+: " + id);
    }

    @Test
    void hashBasedRootIdIsAscii() {
        String id = RootIdGenerator.generateFromContent("纯中文内容测试");
        assertTrue(id.matches("root\\.[a-f0-9]{8}"), "Hash-based rootId should be root.<hash8>: " + id);
    }

    @Test
    void allKnownTopicIdsAreAscii() {
        // 验证所有已知主题映射的 rootId 后缀都是 ASCII
        String[] testInputs = {
                "明日方舟", "泰拉", "罗马尼亚", "东南亚", "1850年代", "架空历史",
                "乌萨斯", "罗德岛", "维多利亚", "炎国", "感染者", "源石", "天灾"
        };
        for (String input : testInputs) {
            String id = RootIdGenerator.suggestRootId(input);
            assertTrue(RootIdGenerator.isValidRootId(id),
                    "suggestRootId('" + input + "') = '" + id + "' should be valid ASCII");
        }
    }
}
