package com.gsim.worldinfo;

import java.util.*;
import java.util.Locale;

/**
 * 倒排索引 -- 全分支链元素的全文关键词索引。
 *
 * <p>基于简单的空白字符分词实现，不依赖 NLP 或嵌入向量。支持关键词搜索、
 * 检查点过滤和分页。索引构建时遍历整个节点链，为每个元素的值和标签建立
 * 词元到元素引用的映射。中文文本通过单字切分支持无空格检索。
 */
public final class KeywordIndex {

    // token → list of element refs containing that token
    private final Map<String, List<ElementRef>> inverted;
    private final List<ElementRef> allRefs; // for scoring / dedup

    private KeywordIndex(Map<String, List<ElementRef>> inverted, List<ElementRef> allRefs) {
        this.inverted = inverted;
        this.allRefs = allRefs;
    }

    /**
     * 从完整节点链构建倒排索引。
     *
     * <p>遍历节点链中所有节点的所有检查点元素，为每个元素的值和标签建立
     * 词元到元素引用的映射。
     *
     * @param chain 节点链（根节点到活跃节点）
     * @return 构建完成的倒排索引实例
     */
    public static KeywordIndex build(List<NodeSnapshot> chain) {
        Map<String, List<ElementRef>> inverted = new HashMap<>();
        List<ElementRef> all = new ArrayList<>();

        for (NodeSnapshot node : chain) {
            for (var entry : node.checkpoints().entrySet()) {
                String cpId = entry.getKey();
                for (Element el : entry.getValue().elements()) {
                    ElementRef ref = ElementRef.from(node.nodeId(), node.turn(), node.worldTime(), cpId, el);
                    all.add(ref);
                    for (String token : tokenize(el.value())) {
                        inverted.computeIfAbsent(token, k -> new ArrayList<>()).add(ref);
                    }
                    for (String tag : el.tags()) {
                        for (String token : tokenize(tag)) {
                            inverted.computeIfAbsent(token, k -> new ArrayList<>())
                                    .add(ref);
                        }
                    }
                }
            }
        }
        return new KeywordIndex(inverted, all);
    }

    /**
     * 根据一个或多个空格分隔的关键词搜索。
     *
     * <p>结果按关键词匹配数评分，并按分数降序、回合数降序排列，支持分页返回。
     *
     * @param keywords 搜索关键词（空格分隔多个关键词）
     * @param limit    每页返回的最大结果数
     * @param offset   分页偏移量
     * @return 搜索结果（含总命中数、偏移量和命中列表）
     */
    public SearchResult search(String keywords, int limit, int offset) {
        return search(keywords, limit, offset, null);
    }

    /**
     * 根据关键词搜索，支持按检查点 ID 过滤。
     *
     * <p>如果指定了 checkpointId，则只返回属于该检查点的元素（精确匹配）。
     * 结果按关键词匹配数评分，并按分数降序、回合数降序排列，支持分页返回。
     *
     * @param keywords     搜索关键词（空格分隔多个关键词）
     * @param limit        每页返回的最大结果数
     * @param offset       分页偏移量
     * @param checkpointId 可选的检查点 ID 过滤条件（为 null 时不限制）
     * @return 搜索结果（含总命中数、偏移量和命中列表）
     */
    public SearchResult search(String keywords, int limit, int offset, String checkpointId) {
        if (keywords == null || keywords.isBlank()) {
            return new SearchResult(0, offset, List.of());
        }

        List<String> tokens = tokenize(keywords);
        if (tokens.isEmpty()) return new SearchResult(0, offset, List.of());

        // score: count of matching tokens per ref (dedup by ref identity)
        Map<ElementRef, Integer> scores = new LinkedHashMap<>();
        for (String token : tokens) {
            for (ElementRef ref : inverted.getOrDefault(token, List.of())) {
                if (checkpointId != null && !checkpointId.equals(ref.checkpointId())) continue;
                scores.merge(ref, 1, Integer::sum);
            }
        }

        // sort by score desc, then by turn desc
        List<Map.Entry<ElementRef, Integer>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> {
            int cmp = Integer.compare(b.getValue(), a.getValue());
            if (cmp != 0) return cmp;
            return Integer.compare(b.getKey().turn(), a.getKey().turn());
        });

        // paginate
        int total = sorted.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        List<SearchHit> hits = new ArrayList<>();
        for (int i = from; i < to; i++) {
            var entry = sorted.get(i);
            hits.add(new SearchHit(
                    entry.getKey(), snippet(entry.getKey().element().value(), tokens.get(0)), entry.getValue()));
        }

        return new SearchResult(total, offset, hits);
    }

    /**
     * 向索引中添加单个元素引用（用于实时更新）。
     *
     * @param ref 要添加的元素引用
     */
    public void add(ElementRef ref) {
        allRefs.add(ref);
        for (String token : tokenize(ref.element().value())) {
            inverted.computeIfAbsent(token, k -> new ArrayList<>()).add(ref);
        }
        for (String tag : ref.element().tags()) {
            for (String token : tokenize(tag)) {
                inverted.computeIfAbsent(token, k -> new ArrayList<>()).add(ref);
            }
        }
    }

    // -- helpers --

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String seg : text.split("[\\s，。、；：！？,.\\-]+")) {
            if (seg.isBlank()) continue;
            seg = seg.trim().toLowerCase(Locale.ROOT);
            tokens.add(seg);
            // unigram tokens so CJK text without spaces is still searchable
            for (int i = 0; i < seg.length(); i++) {
                tokens.add(String.valueOf(seg.charAt(i)));
            }
        }
        return tokens.stream().distinct().toList();
    }

    private static String snippet(String value, String keyword) {
        int idx = value.toLowerCase(Locale.ROOT).indexOf(keyword.toLowerCase(Locale.ROOT));
        if (idx < 0) idx = 0;
        int start = Math.max(0, idx - 20);
        int end = Math.min(value.length(), idx + keyword.length() + 40);
        String s = value.substring(start, end);
        if (start > 0) s = "..." + s;
        if (end < value.length()) s = s + "...";
        return s;
    }

    // -- result types --

    /**
     * 搜索结果 -- 包含总命中数和分页信息。
     *
     * @param totalHits 总命中数
     * @param offset    当前分页偏移量
     * @param items     当前页的命中列表
     */
    public record SearchResult(int totalHits, int offset, List<SearchHit> items) {}

    /**
     * 搜索命中 -- 单个匹配结果。
     *
     * @param elementRef 匹配的元素引用
     * @param snippet    匹配上下文的摘要片段
     * @param score      匹配评分（关键词匹配数）
     */
    public record SearchHit(ElementRef elementRef, String snippet, int score) {}
}
