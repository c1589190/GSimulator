package com.gsim.core.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 共享文本分词工具。
 *
 * <p>逻辑原样迁移自 {@code KeywordIndex}（gsim-core/worldinfo/KeywordIndex.java:142-155），
 * 保证行为完全一致：按空白与中英文标点切分段落，每个段落整体作为词元，再逐字符追加
 * unigram 词元（中文无空格文本因此可按单字检索），最后去重并保持首次出现顺序。
 */
public final class SearchTextTokenizer {

    private SearchTextTokenizer() {}

    /**
     * 对文本分词。
     *
     * @param text 待分词文本
     * @return 去重后的词元列表（保持首次出现顺序）；null 或空白文本返回空列表
     */
    public static List<String> tokenize(String text) {
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
}
