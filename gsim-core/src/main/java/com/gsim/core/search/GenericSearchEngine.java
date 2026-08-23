package com.gsim.core.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 通用文本搜索引擎。
 *
 * <p>基于 {@link SearchTextTokenizer} 分词 + 词频计分：score = 每个 query token 在条目文本中
 * 出现次数之和（大小写不敏感子串计数）。无持久化、无 embedding，供领域搜索工具
 * （world/region/city/hex/doc）复用。
 */
public final class GenericSearchEngine {

    private GenericSearchEngine() {}

    /**
     * 在条目中执行全文搜索。
     *
     * <p>空 query（null/空白）或空条目列表返回空结果（不抛异常）。排序与分页见
     * {@link SearchOptions}；分页在排序之后应用。
     *
     * @param entries 待搜索条目
     * @param query   查询文本
     * @param opts    搜索选项
     * @return 排序并分页后的命中列表（可能为空）
     */
    public static List<SearchHit> search(List<SearchEntry> entries, String query, SearchOptions opts) {
        if (query == null || query.isBlank() || entries == null || entries.isEmpty()) return List.of();

        List<String> tokens = SearchTextTokenizer.tokenize(query);
        if (tokens.isEmpty()) return List.of();

        List<ScoredEntry> scored = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            SearchEntry entry = entries.get(i);
            double score = score(entry.text(), tokens);
            if (score > 0) scored.add(new ScoredEntry(entry, score, i));
        }

        scored.sort(comparator(opts.sortMode()));

        if (opts.limit() <= 0) return List.of();
        int from = Math.max(0, Math.min(opts.offset(), scored.size()));
        int to = Math.min(from + opts.limit(), scored.size());

        List<SearchHit> hits = new ArrayList<>();
        for (int i = from; i < to; i++) {
            ScoredEntry se = scored.get(i);
            hits.add(new SearchHit(
                    se.entry().key(),
                    snippet(se.entry().text(), tokens),
                    se.score(),
                    se.entry().sortKey()));
        }
        return hits;
    }

    // -- scoring --

    private static double score(String text, List<String> tokens) {
        double total = 0;
        for (String token : tokens) {
            total += countOccurrences(text, token);
        }
        return total;
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int from = 0;
        int idx;
        while ((idx = indexOfIgnoreCase(text, token, from)) >= 0) {
            count++;
            from = idx + token.length();
        }
        return count;
    }

    private static int indexOfIgnoreCase(String text, String token, int from) {
        int max = text.length() - token.length();
        for (int i = from; i <= max; i++) {
            if (text.regionMatches(true, i, token, 0, token.length())) return i;
        }
        return -1;
    }

    // -- snippet --

    /**
     * 取文本中首个命中 token 首次出现位置前后各 10 字符（边界钳制）。
     *
     * <p>仅对 score &gt; 0 的条目调用；此时必有至少一个 token 命中，不会返回空串。
     */
    private static String snippet(String text, List<String> tokens) {
        for (String token : tokens) {
            int idx = indexOfIgnoreCase(text, token, 0);
            if (idx < 0) continue;
            int start = Math.max(0, idx - 10);
            int end = Math.min(text.length(), idx + token.length() + 10);
            return text.substring(start, end);
        }
        return "";
    }

    // -- sorting --

    private static Comparator<ScoredEntry> comparator(SearchOptions.SortMode sortMode) {
        // SORT_KEY_DESC 与 SORT_KEY_ASC 互为精确反序（并列时输入序也取反）
        Comparator<ScoredEntry> bySortKey =
                Comparator.comparingLong((ScoredEntry se) -> se.entry().sortKey());
        Comparator<ScoredEntry> byScore = Comparator.comparingDouble(ScoredEntry::score);
        return switch (sortMode) {
            case RELEVANCE -> byScore.reversed()
                    .thenComparing(bySortKey.reversed())
                    .thenComparingInt(ScoredEntry::index);
            case SORT_KEY_ASC -> bySortKey.thenComparing(byScore.reversed()).thenComparingInt(ScoredEntry::index);
            case SORT_KEY_DESC -> bySortKey
                    .reversed()
                    .thenComparing(byScore)
                    .thenComparing(Comparator.comparingInt(ScoredEntry::index).reversed());
        };
    }

    private record ScoredEntry(SearchEntry entry, double score, int index) {}
}
