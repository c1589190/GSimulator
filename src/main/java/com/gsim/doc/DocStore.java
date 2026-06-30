package com.gsim.doc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 统一文档存储 — 管理 {@code data/docs/{type}/{id}.md} 文件的 CRUD。
 *
 * <p>内存缓存 + 文件系统持久化。线程安全（单写入者模式，ToolLoop 内调用）。
 */
public class DocStore {

    private static final Logger log = LoggerFactory.getLogger(DocStore.class);

    private final Path docsDir;
    private final Map<String, Document> cache = new ConcurrentHashMap<>();

    public DocStore(Path docsDir) {
        this.docsDir = docsDir;
    }

    /** 启动时扫描 docs/ 目录重建缓存。 */
    public void init() throws IOException {
        cache.clear();
        Files.createDirectories(docsDir);

        try (Stream<Path> files = Files.walk(docsDir)) {
            for (Path file : files.sorted().toList()) {
                if (!file.getFileName().toString().endsWith(".md")) continue;
                if (file.getParent().getFileName() == null) continue;
                try {
                    Document doc = Document.fromFile(file);
                    cache.put(doc.id(), doc);
                } catch (Exception e) {
                    log.warn("[DocStore] Failed to parse {}, skipping: {}", file, e.getMessage());
                }
            }
        }
        log.info("[DocStore] loaded {} documents from {}", cache.size(), docsDir);
    }

    // ── CRUD ──

    /** 列出所有文档，可按 type/tag 过滤。 */
    public List<Document> list(DocType typeFilter, String tagFilter) {
        return cache.values().stream()
                .filter(d -> typeFilter == null || d.type() == typeFilter)
                .filter(d -> tagFilter == null || tagFilter.isBlank()
                        || d.tags().stream().anyMatch(t -> t.equalsIgnoreCase(tagFilter.trim())))
                .sorted((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()))
                .toList();
    }

    /** 按 ID 获取文档。 */
    public Document get(String id) {
        return cache.get(id);
    }

    /** 创建新文档（ID 冲突时返回 null）。 */
    public Document create(String id, DocType type, String title,
                            String content, List<String> tags) throws IOException {
        if (cache.containsKey(id)) return null;

        Document doc = Document.create(id, type, title, content, tags);
        writeToFile(doc);
        cache.put(id, doc);
        log.info("[DocStore] created: {} (type={})", id, type.key());
        return doc;
    }

    /** 更新文档内容（版本号 +1）。 */
    public Document updateContent(String id, String newContent) throws IOException {
        Document old = cache.get(id);
        if (old == null) return null;

        Document doc = old.withContent(newContent);
        writeToFile(doc);
        cache.put(id, doc);
        return doc;
    }

    /** 更新文档元数据。 */
    public Document updateMeta(String id, String newTitle, List<String> newTags)
            throws IOException {
        Document old = cache.get(id);
        if (old == null) return null;

        Document doc = old.withMeta(newTitle, newTags);
        writeToFile(doc);
        cache.put(id, doc);
        return doc;
    }

    /** 删除文档。 */
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

    /** 检查文档是否存在。 */
    public boolean exists(String id) {
        return cache.containsKey(id);
    }

    /** 文档总数。 */
    public int count() {
        return cache.size();
    }

    /** 重建缓存（用于外部文件变更后）。 */
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
     */
    public int migrateFromSkills(Path skillsDir) throws IOException {
        if (!Files.isDirectory(skillsDir)) return 0;

        int count = 0;
        try (var dirs = Files.newDirectoryStream(skillsDir, p ->
                Files.isDirectory(p) && !p.getFileName().toString().startsWith("."))) {
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

                    Document doc = Document.create(skillId, DocType.SKILL, title,
                            body, List.of());
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
