package com.gsim.doc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一文档模型 — 替换旧 SkillMeta，泛化到所有 Agent 可读写的文本资产。
 *
 * <p>文件格式（YAML frontmatter + Markdown body）：
 * <pre>
 * ---
 * type: character
 * title: 曹操 · 角色设定
 * tags: [角色, 魏, 君主]
 * version: 1
 * ---
 * 曹操，字孟德...
 * </pre>
 */
public record Document(
        String id,
        DocType type,
        String title,
        String content,
        List<String> tags,
        int version,
        long updatedAt) {

    // ── 工厂方法 ──

    public static Document create(String id, DocType type, String title,
                                   String content, List<String> tags) {
        return new Document(id, type, title, content,
                tags != null ? List.copyOf(tags) : List.of(),
                1, System.currentTimeMillis());
    }

    public Document withContent(String newContent) {
        return new Document(id, type, title, newContent, tags,
                version + 1, System.currentTimeMillis());
    }

    public Document withMeta(String newTitle, List<String> newTags) {
        return new Document(id, type, newTitle, content,
                newTags != null ? List.copyOf(newTags) : tags,
                version + 1, System.currentTimeMillis());
    }

    /** 正文前 200 字的摘要。 */
    public String summary() {
        if (content == null || content.isBlank()) return "";
        String stripped = content.replaceAll("^---\\n.*?---\\n", "").trim();
        return stripped.length() <= 200 ? stripped : stripped.substring(0, 197) + "...";
    }

    // ── 序列化 ──

    /** 从文件系统路径读取完整 Document。 */
    public static Document fromFile(Path file) throws IOException {
        String raw = Files.readString(file);
        return fromText(file.getFileName().toString().replace(".md", ""), raw);
    }

    /** 从 markdown 文本解析（含 frontmatter）。 */
    public static Document fromText(String id, String text) {
        if (text == null || text.isBlank()) {
            return new Document(id, DocType.OTHER, id, "", List.of(), 0, 0);
        }

        String[] lines = text.split("\n", -1);
        Map<String, String> fm = new LinkedHashMap<>();

        int bodyStart = 0;
        if (lines.length >= 2 && lines[0].trim().equals("---")) {
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.equals("---")) {
                    bodyStart = i + 1;
                    break;
                }
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                fm.put(key, value);
            }
        }

        String body = String.join("\n",
                java.util.Arrays.copyOfRange(lines, bodyStart, lines.length));

        DocType type = DocType.fromKey(fm.getOrDefault("type", "other"));
        String title = fm.getOrDefault("title", id);
        List<String> tags = parseTags(fm.getOrDefault("tags", ""));
        int version = parseInt(fm.getOrDefault("version", "1"), 1);
        long updatedAt = parseLong(fm.getOrDefault("updated", ""), System.currentTimeMillis());

        return new Document(id, type, title, body, tags, version, updatedAt);
    }

    /** 生成带 frontmatter 的完整文件内容。 */
    public String toFileContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("type: ").append(type.key()).append("\n");
        sb.append("title: ").append(title).append("\n");
        if (!tags.isEmpty()) {
            sb.append("tags: [").append(String.join(", ", tags)).append("]\n");
        }
        sb.append("version: ").append(version).append("\n");
        sb.append("updated: ").append(updatedAt).append("\n");
        sb.append("---\n");
        sb.append(content != null ? content : "");
        sb.append("\n");
        return sb.toString();
    }

    /** 用于 embedding 计算的文本。 */
    public String embeddingText() {
        return type.key() + " " + title + " " + summary();
    }

    // ── 辅助 ──

    private static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        return List.of(s.split("\\s*,\\s*")).stream()
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
}
