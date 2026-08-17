package com.gsim.core.doc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一文档存储 — 管理 {@code data/docs/{type}/{id}.md} 文件的 CRUD。
 *
 * <p>内存缓存 + 文件系统持久化。线程安全（单写入者模式，ToolLoop 内调用）。
 */
public class DocStore {

    private static final Logger log = LoggerFactory.getLogger(DocStore.class);

    private final Path docsDir;
    private final Map<String, Document> cache = new ConcurrentHashMap<>();

    /**
     * 创建文档存储实例。
     *
     * @param docsDir 文档存储根目录
     */
    public DocStore(Path docsDir) {
        this.docsDir = docsDir;
    }

    /**
     * 启动时扫描 docs/ 目录重建缓存。
     *
     * @throws IOException 文件系统扫描或目录创建失败时抛出
     */
    public void init() throws IOException {
        cache.clear();
        Files.createDirectories(docsDir);

        try (Stream<Path> files = Files.walk(docsDir)) {
            for (Path file : files.sorted().toList()) {
                if (!file.getFileName().toString().endsWith(".md")) continue;
                if (file.getParent().getFileName() == null) continue;
                try {
                    Document doc = Document.fromFile(file);
                    // docId = relative path from docsDir, strip .md
                    String relPath = docsDir.relativize(file).toString();
                    String docId = relPath.substring(0, relPath.length() - 3); // strip .md
                    // 首段是已知 DocType.key → 布局段，剥掉（docId 不含 type 前缀）
                    int slash = docId.indexOf('/');
                    if (slash > 0) {
                        for (DocType t : DocType.values()) {
                            if (t.key().equals(docId.substring(0, slash))) {
                                docId = docId.substring(slash + 1);
                                break;
                            }
                        }
                    }
                    doc = Document.create(docId, doc.type(), doc.title(), doc.content(), doc.tags());
                    cache.put(docId, doc);
                } catch (Exception e) {
                    log.warn("[DocStore] Failed to parse {}, skipping: {}", file, e.getMessage());
                }
            }
        }
        log.info("[DocStore] loaded {} documents from {}", cache.size(), docsDir);
    }

    // ── CRUD ──

    /**
     * 列出所有文档，可按 type/tag 过滤。
     *
     * @param typeFilter 文档类型过滤器（null 表示不过滤）
     * @param tagFilter  标签过滤器（null 或空白表示不过滤）
     * @return 符合条件的文档列表，按更新时间降序排列
     */
    public List<Document> list(DocType typeFilter, String tagFilter) {
        return cache.values().stream()
                .filter(d -> typeFilter == null || d.type() == typeFilter)
                .filter(d -> tagFilter == null
                        || tagFilter.isBlank()
                        || d.tags().stream().anyMatch(t -> t.equalsIgnoreCase(tagFilter.trim())))
                .sorted((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()))
                .toList();
    }

    /**
     * 按类型列出 docId 以指定前缀开头的文档（去重扫描用，避免全量遍历）。
     *
     * @param type     文档类型过滤器
     * @param idPrefix docId 前缀（null 表示不过滤）
     * @return 符合条件的文档列表，按更新时间降序排列
     */
    public List<Document> listByTypeAndPrefix(DocType type, String idPrefix) {
        return list(type, null).stream()
                .filter(d -> idPrefix == null || d.id().startsWith(idPrefix))
                .toList();
    }

    /**
     * 删除指定类型中最后修改时间早于截止时间的文档（GC）。
     *
     * <p>仅按文件系统 mtime 判定；文件缺失时跳过（幂等，不抛错）。只影响传入的
     * {@code type}，其他类型不受影响。调用方仅用于 {@link DocType#TMP} 清扫。
     *
     * @param type   要清扫的文档类型
     * @param cutoff 截止时间，mtime 严格早于该时刻的文档才会被删除
     * @return 实际删除的文档数量
     * @throws IOException 文件系统读取/删除失败时抛出
     */
    public int deleteByTypeOlderThan(DocType type, Instant cutoff) throws IOException {
        int removed = 0;
        for (Document doc : list(type, null)) {
            Path file = fileFor(doc);
            if (!Files.isRegularFile(file)) continue; // 文件缺失 → 跳过
            Instant lastModified = Files.getLastModifiedTime(file).toInstant();
            if (!lastModified.isBefore(cutoff)) continue; // 未过期 → 保留
            Files.deleteIfExists(file);
            cache.remove(doc.id());
            removed++;
            log.info("[DocStore] GC removed stale {} doc: {} (mtime {})", type.key(), doc.id(), lastModified);
        }
        return removed;
    }

    /**
     * 按 ID 获取文档。
     *
     * @param id 文档 ID
     * @return 文档对象，不存在时返回 null
     */
    public Document get(String id) {
        return cache.get(id);
    }

    /**
     * 创建新文档（ID 冲突时返回 null）。
     *
     * @param id      文档唯一 ID
     * @param type    文档类型
     * @param title   文档标题
     * @param content 文档正文（Markdown 格式）
     * @param tags    文档标签列表
     * @return 创建成功返回 Document 对象；ID 已存在时返回 null
     * @throws IOException 文件写入失败时抛出
     */
    public Document create(String id, DocType type, String title, String content, List<String> tags)
            throws IOException {
        if (cache.containsKey(id)) return null;

        Document doc = Document.create(id, type, title, content, tags);
        writeToFile(doc);
        cache.put(id, doc);
        log.info("[DocStore] created: {} (type={})", id, type.key());
        return doc;
    }

    /**
     * 更新文档内容（版本号 +1）。
     *
     * @param id         文档 ID
     * @param newContent 新的文档正文
     * @return 更新后的 Document 对象；文档不存在时返回 null
     * @throws IOException 文件写入失败时抛出
     */
    public Document updateContent(String id, String newContent) throws IOException {
        Document old = cache.get(id);
        if (old == null) return null;

        Document doc = old.withContent(newContent);
        writeToFile(doc);
        cache.put(id, doc);
        return doc;
    }

    /**
     * 更新文档元数据（标题和标签）。
     *
     * @param id       文档 ID
     * @param newTitle 新标题
     * @param newTags  新标签列表
     * @return 更新后的 Document 对象；文档不存在时返回 null
     * @throws IOException 文件写入失败时抛出
     */
    public Document updateMeta(String id, String newTitle, List<String> newTags) throws IOException {
        Document old = cache.get(id);
        if (old == null) return null;

        Document doc = old.withMeta(newTitle, newTags);
        writeToFile(doc);
        cache.put(id, doc);
        return doc;
    }

    /**
     * 删除文档。
     *
     * @param id 文档 ID
     * @return 删除成功返回 true；文档不存在时返回 false
     * @throws IOException 文件删除失败时抛出
     */
    public boolean delete(String id) throws IOException {
        Document doc = cache.remove(id);
        if (doc == null) return false;

        Path file = fileFor(doc);
        Files.deleteIfExists(file);
        // 如目录为空则一并删除
        Path dir = file.getParent();
        try (Stream<Path> s = Files.list(dir)) {
            if (s.findAny().isEmpty()) Files.deleteIfExists(dir);
        }
        log.info("[DocStore] deleted: {}", id);
        return true;
    }

    /**
     * 检查文档是否存在。
     *
     * @param id 文档 ID
     * @return 文档存在时返回 true
     */
    public boolean exists(String id) {
        return cache.containsKey(id);
    }

    /**
     * 返回文档总数。
     *
     * @return 缓存中的文档数量
     */
    public int count() {
        return cache.size();
    }

    /**
     * 重建缓存（用于外部文件变更后）。
     *
     * @throws IOException 文件系统扫描失败时抛出
     */
    public void reload() throws IOException {
        init();
    }

    // ── 内部 ──

    private Path fileFor(Document doc) {
        return docsDir.resolve(doc.type().key()).resolve(doc.id() + ".md");
    }

    private void writeToFile(Document doc) throws IOException {
        Path file = fileFor(doc);
        Files.createDirectories(file.getParent());
        Files.writeString(file, doc.toFileContent());
    }

    // ── 迁移 ──

    /**
     * 从旧 data/skills/ 目录迁移到 data/docs/skill/。
     * 返回迁移数量。迁移后不删除旧文件。
     *
     * @param skillsDir 旧 skills 目录路径
     * @return 已迁移的 skill 数量
     * @throws IOException 文件系统操作失败时抛出
     */
    public int migrateFromSkills(Path skillsDir) throws IOException {
        if (!Files.isDirectory(skillsDir)) return 0;

        int count = 0;
        try (var dirs = Files.newDirectoryStream(
                skillsDir,
                p -> Files.isDirectory(p) && !p.getFileName().toString().startsWith("."))) {
            for (Path dir : dirs) {
                String skillId = dir.getFileName().toString();
                Path mdFile = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(mdFile)) continue;

                // Check if already migrated
                if (cache.containsKey(skillId)) {
                    log.debug("[DocStore] skill {} already migrated, skipping", skillId);
                    continue;
                }

                try {
                    String raw = Files.readString(mdFile);
                    // Old SkillMeta frontmatter uses name/description, map to title
                    String title = skillId;
                    String body = raw;
                    // Try parsing old format frontmatter
                    if (raw.startsWith("---")) {
                        int end = raw.indexOf("---", 3);
                        if (end > 0) {
                            String fmText = raw.substring(4, end).trim();
                            for (String line : fmText.split("\n")) {
                                int c = line.indexOf(':');
                                if (c < 0) continue;
                                String key = line.substring(0, c).trim();
                                String val = line.substring(c + 1).trim();
                                if ("name".equals(key)) title = val;
                            }
                            body = raw.substring(end + 3).trim();
                        }
                    }

                    Document doc = Document.create(skillId, DocType.SKILL, title, body, List.of());
                    writeToFile(doc);
                    cache.put(skillId, doc);
                    count++;
                    log.info("[DocStore] migrated skill: {} → docs/skill/{}.md", skillId, skillId);
                } catch (Exception e) {
                    log.warn("[DocStore] failed to migrate skill {}: {}", skillId, e.getMessage());
                }
            }
        }
        log.info("[DocStore] migrated {} skills to docs/", count);
        return count;
    }
}
