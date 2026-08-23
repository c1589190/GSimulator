package com.gsim.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link SearchTextTokenizer} 行为回归测试。
 *
 * <p>分词逻辑原样迁移自 {@code KeywordIndex}（gsim-core/worldinfo/KeywordIndex.java:142-155），
 * 因此断言的是迁移后的真实输出：整段词元 + 逐字符 unigram（全字符，不仅 CJK）+ 去重保序。
 */
class SearchTextTokenizerTest {

    @Test
    void cjkTextYieldsSegmentPlusPerCharUnigrams() {
        // 整段词元 "迷雾森林" + 每个字符 unigram（去重保序）
        assertEquals(List.of("迷雾森林", "迷", "雾", "森", "林"), SearchTextTokenizer.tokenize("迷雾森林"));
    }

    @Test
    void englishTextYieldsWordsPlusLetterUnigrams() {
        // "river ford" → 段 "river"/"ford" + 逐字母 unigram，去重保序
        assertEquals(
                List.of("river", "r", "i", "v", "e", "ford", "f", "o", "d"),
                SearchTextTokenizer.tokenize("river ford"));
    }

    @Test
    void mixedCjkAndEnglishText() {
        assertEquals(
                List.of("迷雾", "迷", "雾", "forest", "f", "o", "r", "e", "s", "t", "森林", "森", "林"),
                SearchTextTokenizer.tokenize("迷雾 forest 森林"));
    }

    @Test
    void punctuationActsAsSeparator() {
        assertEquals(
                List.of("中原大旱", "中", "原", "大", "旱", "蝗灾四起", "蝗", "灾", "四", "起"),
                SearchTextTokenizer.tokenize("中原大旱，蝗灾四起"));
    }

    @Test
    void whitespaceAndHyphensAreSeparators() {
        assertEquals(
                List.of("well", "w", "e", "l", "known", "k", "n", "o", "ford", "f", "r", "d"),
                SearchTextTokenizer.tokenize("well-known ford"));
    }

    @Test
    void blankAndNullReturnEmpty() {
        assertEquals(List.of(), SearchTextTokenizer.tokenize(null));
        assertEquals(List.of(), SearchTextTokenizer.tokenize(""));
        assertEquals(List.of(), SearchTextTokenizer.tokenize("   "));
        assertEquals(List.of(), SearchTextTokenizer.tokenize("，。、"));
    }

    @Test
    void tokensAreLowercased() {
        assertEquals(List.of("river", "r", "i", "v", "e"), SearchTextTokenizer.tokenize("River"));
    }

    @Test
    void duplicatesAreRemovedPreservingFirstOccurrenceOrder() {
        assertEquals(List.of("river", "r", "i", "v", "e"), SearchTextTokenizer.tokenize("river river"));
    }
}
