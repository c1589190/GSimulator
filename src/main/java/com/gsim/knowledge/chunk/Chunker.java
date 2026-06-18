package com.gsim.knowledge.chunk;

import com.gsim.knowledge.KnowledgeChunk;
import com.gsim.util.IdGenerator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 文本切块器 — 将长文本按固定字符窗口切分为 chunks。
 * 第一版使用简单滑动窗口，不做 NLP 句子边界检测。
 */
public class Chunker {

    /** 默认每块最大字符数 */
    public static final int DEFAULT_CHUNK_SIZE = 800;
    /** 滑动窗口重叠字符数 */
    public static final int DEFAULT_OVERLAP = 100;

    private final int chunkSize;
    private final int overlap;

    public Chunker() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    public Chunker(int chunkSize, int overlap) {
        if (chunkSize <= overlap) {
            throw new IllegalArgumentException("chunkSize must be > overlap");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /**
     * 将文本切分为 chunks。
     */
    public List<KnowledgeChunk> chunk(String docId, String title, String content,
                                       String collection, String metadataJson) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        String now = Instant.now().toString();
        int textLen = content.length();

        if (textLen <= chunkSize) {
            // 短文本，单 chunk
            chunks.add(createChunk(docId, title, content, collection, metadataJson,
                    0, 0, textLen, now));
            return chunks;
        }

        int start = 0;
        int index = 0;
        while (start < textLen) {
            int end = Math.min(start + chunkSize, textLen);
            String chunkText = content.substring(start, end);
            chunks.add(createChunk(docId, title, chunkText, collection, metadataJson,
                    index, start, end, now));
            index++;
            if (end >= textLen) break;
            start = end - overlap;
        }
        return chunks;
    }

    private KnowledgeChunk createChunk(String docId, String title, String text,
                                        String collection, String metadataJson,
                                        int index, int startChar, int endChar, String now) {
        String chunkId = IdGenerator.knowledgeChunkId();
        String hash = sha256(text);
        return new KnowledgeChunk(chunkId, docId, collection, title, text, index,
                startChar, endChar, hash, metadataJson, now, now);
    }

    /** 计算字符串 SHA-256 hex 摘要。 */
    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
