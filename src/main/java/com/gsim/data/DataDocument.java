package com.gsim.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析后的 Markdown 文档，包含 YAML front matter 和正文。
 */
public class DataDocument {

    private final String id;
    private final String type;
    private final String role;
    private final String name;
    private final List<String> aliases;
    private final List<String> tags;
    private final String updated;
    private final String body;
    private final String rawPath;     // 相对于 branch 根目录的路径
    private final Map<String, String> frontMatter;

    public DataDocument(Map<String, String> frontMatter, String body, String rawPath) {
        this.frontMatter = Collections.unmodifiableMap(new LinkedHashMap<>(frontMatter));
        this.id = frontMatter.getOrDefault("id", pathToId(rawPath));
        this.type = frontMatter.getOrDefault("type", "");
        this.role = frontMatter.getOrDefault("role", "");
        this.name = frontMatter.getOrDefault("name", "");
        this.aliases = parseList(frontMatter.get("aliases"));
        this.tags = parseList(frontMatter.get("tags"));
        this.updated = frontMatter.getOrDefault("updated", "");
        this.body = body != null ? body : "";
        this.rawPath = rawPath != null ? rawPath : "";
    }

    public static DataDocument withoutFrontMatter(String body, String rawPath) {
        return new DataDocument(Map.of(), body, rawPath);
    }

    // ---- getters ----

    public String id() { return id; }
    public String type() { return type; }
    public String role() { return role; }
    public String name() { return name; }
    public List<String> aliases() { return aliases; }
    public List<String> tags() { return tags; }
    public String updated() { return updated; }
    public String body() { return body; }
    public String rawPath() { return rawPath; }
    public Map<String, String> frontMatter() { return frontMatter; }

    /** 如果此节点是 compact 检查点，返回被压缩的父节点 ID；否则返回 null。 */
    public String compactOf() { return frontMatter.get("compactOf"); }

    /** 此节点是否为 compact 检查点。 */
    public boolean isCompact() { return frontMatter.containsKey("compactOf"); }

    /** 完整文档内容（含 front matter），用于显示和写入。 */
    public String fullContent() {
        StringBuilder sb = new StringBuilder();
        if (!frontMatter.isEmpty()) {
            for (var entry : frontMatter.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            sb.append("-------------------\n\n");
        }
        sb.append(body);
        return sb.toString();
    }

    // ---- helpers ----

    private static String pathToId(String path) {
        if (path == null || path.isBlank()) return "unknown";
        return path.replace(".md", "")
                .replace("/", ".")
                .replace("\\", ".")
                .replaceAll("^branches\\.[^.]+\\." , "");
    }

    private static List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String cleaned = raw.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.isBlank()) return List.of();
        return List.of(cleaned.split("\\s*,\\s*"));
    }
}
