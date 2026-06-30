package com.gsim.doc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * doc 工具输出缓存管理器 — 将 doc 工具生成的大段文本缓存为本地文件，
 * 返回 {@code @cache:{id}} 短引用供 LLM 在后续工具调用中使用。
 *
 * <p>存储位置：{@code data/docs/.cache/}
 *
 * <h3>引用格式</h3>
 * <pre>@cache:crop_20260701_120000_a1b2c3d4</pre>
 *
 * <h3>解析规则</h3>
 * <ul>
 *   <li>以 @cache: 开头 → 提取 ID，读取文件返回全文</li>
 *   <li>不含 @cache: → 原样返回</li>
 * </ul>
 */
public class DocCacheManager {

    private static final Logger log = LoggerFactory.getLogger(DocCacheManager.class);

    public static final String CACHE_PREFIX = "@cache:";

    private final Path cacheDir;

    public DocCacheManager(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** 确保缓存目录存在。 */
    public void init() throws IOException {
        Files.createDirectories(cacheDir);
    }

    /**
     * 缓存一段文本。
     *
     * @param toolName 工具名（用于 cacheId 前缀）
     * @param text     要缓存的文本
     * @return cacheId
     */
    public String put(String toolName, String text) throws IOException {
        init();
        String id = generateId(toolName);
        Path file = cacheDir.resolve(id + ".txt");
        Files.writeString(file, text);
        log.debug("[DocCache] cached {} chars to {}", text.length(), id);
        return id;
    }

    /**
     * 按 cacheId 读取缓存内容。
     *
     * @param cacheId 缓存 ID
     * @return 文件内容，不存在返回 null
     */
    public String get(String cacheId) {
        Path file = cacheDir.resolve(cacheId + ".txt");
        if (!Files.isRegularFile(file)) {
            log.warn("[DocCache] cache not found: {}", cacheId);
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            log.warn("[DocCache] failed to read cache {}: {}", cacheId, e.getMessage());
            return null;
        }
    }

    /**
     * 尝试将 @cache:{id} 解析为文档内容。
     * 扫描 rawDocId 中任意位置的 @cache:{id} 引用，解析后返回缓存全文。
     * 返回 null 表示不含有效 @cache: 引用。
     * 用于任何接受 docId 参数的工具 — 直接调用此方法即可透明支持虚拟缓存文档。
     */
    public String resolveDocId(String rawDocId) {
        if (rawDocId == null || rawDocId.isBlank()) return null;
        String trimmed = rawDocId.trim();

        // 如果整个 docId 就是 @cache:xxx → 直接返回全文
        if (trimmed.startsWith(CACHE_PREFIX)) {
            String cacheId = trimmed.substring(CACHE_PREFIX.length()).trim();
            if (!cacheId.isEmpty()) {
                String cached = get(cacheId);
                if (cached != null) return cached;
            }
        }

        // 扫描 docId 中内嵌的 @cache:xxx 引用
        int pos = 0;
        while ((pos = trimmed.indexOf(CACHE_PREFIX, pos)) >= 0) {
            int idStart = pos + CACHE_PREFIX.length();
            int idEnd = idStart;
            while (idEnd < trimmed.length()
                    && !Character.isWhitespace(trimmed.charAt(idEnd))
                    && trimmed.charAt(idEnd) != ','
                    && trimmed.charAt(idEnd) != ';') {
                idEnd++;
            }
            if (idEnd > idStart) {
                String cacheId = trimmed.substring(idStart, idEnd);
                String cached = get(cacheId);
                if (cached != null) return cached;
            }
            pos = idEnd;
        }
        return null;
    }

    /**
     * 解析文本中的 @cache: 引用。
     * <ul>
     *   <li>整个文本以 @cache:{id} 开头 → 替换为缓存全文</li>
     *   <li>文本中含 @cache:{id} → 替换为缓存全文</li>
     *   <li>无引用 → 原样返回</li>
     * </ul>
     */
    public String resolve(String text) {
        if (text == null || text.isBlank()) return text;

        // 整个文本就是 @cache:xxx → 完整替换
        String trimmed = text.trim();
        if (trimmed.startsWith(CACHE_PREFIX)) {
            // 提取 ID（去掉前缀，取到空格、逗号、分号、句号或行尾）
            String rest = trimmed.substring(CACHE_PREFIX.length()).trim();
            // 取第一个分隔符之前的部分作为 cacheId
            String cacheId = rest.split("[\\s,;。\n]", 2)[0];
            String cached = get(cacheId);
            if (cached != null) {
                // 如果 @cache: 后面还有附加文本，追加到缓存内容后
                String suffix = rest.substring(cacheId.length()).trim();
                if (!suffix.isEmpty()) {
                    cached = cached + "\n" + suffix;
                }
                return cached;
            }
            // 缓存未找到 → 返回原始引用 + 警告
            log.warn("[DocCache] unresolved reference: {}", cacheId);
            return text;
        }

        // 文本中包含 @cache:xxx → 替换
        if (text.contains(CACHE_PREFIX)) {
            String result = text;
            int pos = 0;
            while ((pos = result.indexOf(CACHE_PREFIX, pos)) >= 0) {
                int start = pos;
                int end = start + CACHE_PREFIX.length();
                while (end < result.length()
                        && !Character.isWhitespace(result.charAt(end))
                        && result.charAt(end) != ','
                        && result.charAt(end) != ';'
                        && result.charAt(end) != '。'
                        && result.charAt(end) != '\n') {
                    end++;
                }
                String cacheId = result.substring(start + CACHE_PREFIX.length(), end);
                if (cacheId.isEmpty()) {
                    pos = end; // 跳过无有效 ID 的 @cache: 文本
                    continue;
                }
                String cached = get(cacheId);
                if (cached != null) {
                    result = result.substring(0, start) + cached + result.substring(end);
                    pos = start + cached.length();
                } else {
                    pos = end;
                }
            }
            return result;
        }

        return text;
    }

    /** 列出所有缓存信息。 */
    public List<CacheInfo> list() {
        List<CacheInfo> result = new ArrayList<>();
        if (!Files.isDirectory(cacheDir)) return result;
        try (Stream<Path> files = Files.list(cacheDir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".txt")) continue;
                String id = name.replace(".txt", "");
                try {
                    long size = Files.size(file);
                    result.add(new CacheInfo(id, size));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            log.warn("[DocCache] failed to list cache: {}", e.getMessage());
        }
        return result;
    }

    /** 清理超过指定天数的缓存。返回清理数量。 */
    public int cleanupOlderThan(int days) throws IOException {
        if (!Files.isDirectory(cacheDir)) return 0;
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        int count = 0;
        try (Stream<Path> files = Files.list(cacheDir)) {
            for (Path file : files.toList()) {
                if (!file.getFileName().toString().endsWith(".txt")) continue;
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        Files.delete(file);
                        count++;
                    }
                } catch (IOException ignored) {
                }
            }
        }
        if (count > 0) log.info("[DocCache] cleaned up {} expired caches (>{} days)", count, days);
        return count;
    }

    /** 缓存总数。 */
    public int count() {
        if (!Files.isDirectory(cacheDir)) return 0;
        try (Stream<Path> files = Files.list(cacheDir)) {
            return (int) files.filter(f -> f.getFileName().toString().endsWith(".txt")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    // ── 内部 ──

    private String generateId(String toolName) {
        String date = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String random = randomHex(8);
        return toolName + "_" + date + "_" + random;
    }

    private static String randomHex(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(Integer.toHexString(ThreadLocalRandom.current().nextInt(16)));
        }
        return sb.toString();
    }

    // ── record ──

    public record CacheInfo(String id, long size) {}
}
