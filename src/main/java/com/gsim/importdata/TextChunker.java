package com.gsim.importdata;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分段器 — 将长文本按段落或字符数分段。
 */
public class TextChunker {

    private static final int DEFAULT_MAX_CHUNK_CHARS = 2000;

    private final int maxChunkChars;

    public TextChunker() {
        this(DEFAULT_MAX_CHUNK_CHARS);
    }

    public TextChunker(int maxChunkChars) {
        this.maxChunkChars = maxChunkChars;
    }

    /**
     * 将文本分段。
     */
    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 先尝试按段落分
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (current.length() + trimmed.length() > maxChunkChars && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }

            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }

        // 如果分段后还有超长的 chunk，按字符数强制分割
        List<String> finalChunks = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() > maxChunkChars) {
                finalChunks.addAll(splitByLength(chunk));
            } else {
                finalChunks.add(chunk);
            }
        }

        return finalChunks;
    }

    private List<String> splitByLength(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChunkChars, text.length());
            // 尽量在句子边界处断
            if (end < text.length()) {
                int lastPeriod = text.lastIndexOf("。", end);
                int lastNewline = text.lastIndexOf("\n", end);
                int breakPoint = Math.max(lastPeriod, lastNewline);
                if (breakPoint > start) {
                    end = breakPoint + 1;
                }
            }
            parts.add(text.substring(start, end).trim());
            start = end;
        }
        return parts;
    }
}
