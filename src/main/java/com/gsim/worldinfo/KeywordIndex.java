package com.gsim.worldinfo;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Full-text keyword inverted index over all elements in the branch chain.
 * Simple whitespace tokenisation — no NLP, no embeddings.
 */
public final class KeywordIndex {

    // token → list of element refs containing that token
    private final Map<String, List<ElementRef>> inverted;
    private final List<ElementRef> allRefs; // for scoring / dedup

    private KeywordIndex(Map<String, List<ElementRef>> inverted, List<ElementRef> allRefs) {
        this.inverted = inverted;
        this.allRefs = allRefs;
    }

    /** Build the index from a full node chain. */
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
                            inverted.computeIfAbsent(token, k -> new ArrayList<>()).add(ref);
                        }
                    }
                }
            }
        }
        return new KeywordIndex(inverted, all);
    }

    /**
     * Search by one or more space-separated keywords.
     * Results are scored by keyword match count and returned with pagination.
     */
    public SearchResult search(String keywords, int limit, int offset) {
        return search(keywords, limit, offset, null);
    }

    /**
     * Search with optional checkpointId filter — only returns elements from the
     * specified checkpoint (exact match).
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
            hits.add(new SearchHit(entry.getKey(), snippet(entry.getKey().element().value(), tokens.get(0)), entry.getValue()));
        }

        return new SearchResult(total, offset, hits);
    }

    /** Add a single ref to the index (for live updates). */
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
            seg = seg.trim().toLowerCase();
            tokens.add(seg);
            // unigram tokens so CJK text without spaces is still searchable
            for (int i = 0; i < seg.length(); i++) {
                tokens.add(String.valueOf(seg.charAt(i)));
            }
        }
        return tokens.stream().distinct().toList();
    }

    private static String snippet(String value, String keyword) {
        int idx = value.toLowerCase().indexOf(keyword.toLowerCase());
        if (idx < 0) idx = 0;
        int start = Math.max(0, idx - 20);
        int end = Math.min(value.length(), idx + keyword.length() + 40);
        String s = value.substring(start, end);
        if (start > 0) s = "..." + s;
        if (end < value.length()) s = s + "...";
        return s;
    }

    // -- result types --

    public record SearchResult(int totalHits, int offset, List<SearchHit> items) {}

    public record SearchHit(ElementRef elementRef, String snippet, int score) {}
}
