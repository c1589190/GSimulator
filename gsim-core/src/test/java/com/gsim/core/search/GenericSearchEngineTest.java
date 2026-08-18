package com.gsim.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link GenericSearchEngine} 行为测试。
 *
 * <p>评分规则：score = 每个 query token 在条目文本中出现的次数之和（大小写不敏感子串计数）；
 * query 与条目文本均先经 {@link SearchTextTokenizer} 处理（段词元 + 逐字符 unigram）。
 */
class GenericSearchEngineTest {

    private static final List<SearchEntry> ENTRIES = List.of(
            new SearchEntry("迷雾森林深处有座古堡", "a", 1L),
            new SearchEntry("河谷平原适合耕种", "b", 2L),
            new SearchEntry("山涧溪流汇入河谷", "c", 3L));

    private static SearchOptions opts(int limit, int offset, SearchOptions.SortMode mode) {
        return new SearchOptions(limit, offset, mode);
    }

    @Test
    void cjkQueryFindsEntryAndSnippetContainsQuery() {
        var hits = GenericSearchEngine.search(ENTRIES, "森林", opts(10, 0, SearchOptions.SortMode.RELEVANCE));

        assertEquals(1, hits.size());
        assertEquals("a", hits.get(0).key());
        assertTrue(hits.get(0).snippet().contains("森林"));
        // query "森林" → tokens [森林, 森, 林]，各命中 1 次 → score 3
        assertEquals(3.0, hits.get(0).score());
    }

    @Test
    void relevanceOrdersByScoreDescThenSortKeyDesc() {
        List<SearchEntry> entries = List.of(
                new SearchEntry("河谷", "low", 1L),
                new SearchEntry("河谷", "high", 2L),
                new SearchEntry("山涧", "single", 9L));

        var hits = GenericSearchEngine.search(entries, "河谷", opts(10, 0, SearchOptions.SortMode.RELEVANCE));

        // query "河谷" → tokens [河谷, 河, 谷]；两条同分 3 → sortKey desc → high(2) 在前；"山涧" 无命中
        assertEquals(List.of("high", "low"), hits.stream().map(SearchHit::key).toList());
    }

    @Test
    void englishScoringCountsOccurrencesOfEveryQueryToken() {
        List<SearchEntry> entries = List.of(new SearchEntry("river ford river", "x", 0L));

        var hits = GenericSearchEngine.search(entries, "river ford", opts(10, 0, SearchOptions.SortMode.RELEVANCE));

        assertEquals(1, hits.size());
        // tokens [river, r, i, v, e, ford, f, o, d]；文本 "river ford river" 中各 token 出现次数：
        // river=2, r=5, i=2, v=2, e=2, ford=1, f=1, o=1, d=1 → score = 17
        assertEquals(17.0, hits.get(0).score());
        assertTrue(hits.get(0).snippet().toLowerCase().contains("river"));
    }

    @Test
    void sortKeyAscAndDescAreExactReverses() {
        List<SearchEntry> entries = List.of(
                new SearchEntry("河流", "k1", 5L),
                new SearchEntry("河畔", "k2", 3L),
                new SearchEntry("河床", "k3", 3L),
                new SearchEntry("山涧", "k4", 9L));

        var asc = GenericSearchEngine.search(entries, "河", opts(10, 0, SearchOptions.SortMode.SORT_KEY_ASC));
        var desc = GenericSearchEngine.search(entries, "河", opts(10, 0, SearchOptions.SortMode.SORT_KEY_DESC));

        // SORT_KEY_DESC 必须与 SORT_KEY_ASC 互为精确反序（含同键并列时的输入序）
        assertEquals(asc.reversed(), desc);
        assertEquals(List.of("k2", "k3", "k1"), asc.stream().map(SearchHit::key).toList());
        assertEquals(
                List.of("k1", "k3", "k2"), desc.stream().map(SearchHit::key).toList());
    }

    @Test
    void sortKeyIsPrimaryThenScoreSecondary() {
        List<SearchEntry> entries = List.of(
                new SearchEntry("河河", "many", 7L), // query "河" 命中 2 次 → score 2
                new SearchEntry("河", "few", 7L)); // score 1

        var asc = GenericSearchEngine.search(entries, "河", opts(10, 0, SearchOptions.SortMode.SORT_KEY_ASC));

        // sortKey 相同(7) → score 降序 → many 在前
        assertEquals(List.of("many", "few"), asc.stream().map(SearchHit::key).toList());
    }

    @Test
    void paginationAppliesAfterSorting() {
        List<SearchEntry> entries = List.of(
                new SearchEntry("河", "k1", 1L),
                new SearchEntry("河", "k2", 2L),
                new SearchEntry("河", "k3", 3L),
                new SearchEntry("河", "k4", 4L),
                new SearchEntry("河", "k5", 5L));
        // RELEVANCE 下同分 → sortKey desc：k5,k4,k3,k2,k1
        assertEquals(
                List.of("k5", "k4"),
                GenericSearchEngine.search(entries, "河", opts(2, 0, SearchOptions.SortMode.RELEVANCE)).stream()
                        .map(SearchHit::key)
                        .toList());
        assertEquals(
                List.of("k3", "k2"),
                GenericSearchEngine.search(entries, "河", opts(2, 2, SearchOptions.SortMode.RELEVANCE)).stream()
                        .map(SearchHit::key)
                        .toList());
        assertEquals(
                List.of("k1"),
                GenericSearchEngine.search(entries, "河", opts(2, 4, SearchOptions.SortMode.RELEVANCE)).stream()
                        .map(SearchHit::key)
                        .toList());
        assertTrue(GenericSearchEngine.search(entries, "河", opts(2, 5, SearchOptions.SortMode.RELEVANCE))
                .isEmpty());
        // limit <= 0 → 空页
        assertTrue(GenericSearchEngine.search(entries, "河", opts(0, 0, SearchOptions.SortMode.RELEVANCE))
                .isEmpty());
    }

    @Test
    void blankQueryReturnsEmptyWithoutThrowing() {
        assertTrue(GenericSearchEngine.search(ENTRIES, "", opts(10, 0, SearchOptions.SortMode.RELEVANCE))
                .isEmpty());
        assertTrue(GenericSearchEngine.search(ENTRIES, "   ", opts(10, 0, SearchOptions.SortMode.RELEVANCE))
                .isEmpty());
        assertTrue(GenericSearchEngine.search(ENTRIES, null, opts(10, 0, SearchOptions.SortMode.RELEVANCE))
                .isEmpty());
    }

    @Test
    void emptyEntriesReturnsEmpty() {
        assertTrue(GenericSearchEngine.search(List.of(), "森林", opts(10, 0, SearchOptions.SortMode.RELEVANCE))
                .isEmpty());
        assertTrue(GenericSearchEngine.search(null, "森林", opts(10, 0, SearchOptions.SortMode.RELEVANCE))
                .isEmpty());
    }

    @Test
    void unknownTokenReturnsEmpty() {
        assertTrue(GenericSearchEngine.search(ENTRIES, "火星", opts(10, 0, SearchOptions.SortMode.RELEVANCE))
                .isEmpty());
    }

    @Test
    void whitespaceOnlyEntryTextIsNeverAHit() {
        List<SearchEntry> entries = List.of(
                new SearchEntry("   ", "blank", 1L),
                new SearchEntry("，，。", "punct", 2L),
                new SearchEntry("迷雾", "real", 3L));

        var hits = GenericSearchEngine.search(entries, "迷雾", opts(10, 0, SearchOptions.SortMode.RELEVANCE));

        assertEquals(List.of("real"), hits.stream().map(SearchHit::key).toList());
    }

    @Test
    void snippetClampsAtStringBoundaries() {
        // 命中在文本开头：start 钳到 0，end = min(len, idx + tokenLen + 10) = 全文
        var headHits = GenericSearchEngine.search(
                List.of(new SearchEntry("迷雾笼罩山野", "a", 1L)), "雾", opts(10, 0, SearchOptions.SortMode.RELEVANCE));
        assertEquals("迷雾笼罩山野", headHits.get(0).snippet());

        // 长文本中命中在末尾：start = idx - 10，end 钳到文本长度
        String longText = "x".repeat(28) + "古";
        var tailHits = GenericSearchEngine.search(
                List.of(new SearchEntry(longText, "b", 1L)), "古", opts(10, 0, SearchOptions.SortMode.RELEVANCE));
        assertEquals(longText.substring(18), tailHits.get(0).snippet());
    }

    @Test
    void snippetUsesFirstMatchingTokenOccurrence() {
        String text = "河谷" + "y".repeat(20) + "河谷";
        var hits = GenericSearchEngine.search(
                List.of(new SearchEntry(text, "a", 1L)), "河谷", opts(10, 0, SearchOptions.SortMode.RELEVANCE));
        // 首个命中 token "河谷" 出现在 index 0 → snippet = [0, 0 + 2 + 10)
        assertEquals(text.substring(0, 12), hits.get(0).snippet());
    }
}
