package com.gsim.skill;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill 元数据 — 从 SKILL.md 的 frontmatter 块解析。
 *
 * <p>格式：
 * <pre>
 * ---
 * name: my-skill
 * description: 一句话描述
 * ---
 * </pre>
 *
 * <p>不使用 YAML 库，简单行解析 {@code key: value}。
 */
public record SkillMeta(String name, String description, Map<String, String> extra) {

    public static final String DEFAULT_NAME = "unnamed";
    public static final String DEFAULT_DESC = "";

    /** 从 SKILL.md 文件路径解析 frontmatter。 */
    public static SkillMeta fromFile(Path skillFile) throws IOException {
        String content = Files.readString(skillFile);
        return fromText(content);
    }

    /** 从 markdown 文本解析 frontmatter。 */
    public static SkillMeta fromText(String text) {
        if (text == null || text.isBlank()) {
            return new SkillMeta(DEFAULT_NAME, DEFAULT_DESC, Map.of());
        }
        String[] lines = text.split("\n");
        if (lines.length < 3 || !lines[0].trim().equals("---")) {
            return new SkillMeta(DEFAULT_NAME, DEFAULT_DESC, Map.of());
        }

        String name = DEFAULT_NAME;
        String description = DEFAULT_DESC;
        Map<String, String> extra = new LinkedHashMap<>();

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equals("---")) break;  // end of frontmatter
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "name" -> name = value;
                case "description" -> description = value;
                default -> extra.put(key, value);
            }
        }

        return new SkillMeta(name, description, Map.copyOf(extra));
    }

    /** 生成带 frontmatter 的完整 SKILL.md 内容。 */
    public String toFrontmatter() {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append("\n");
        sb.append("description: ").append(description).append("\n");
        for (var entry : extra.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        sb.append("---\n");
        return sb.toString();
    }

    /** 用于 embedding 计算的文本：name + " " + description。 */
    public String embeddingText() {
        return name + " " + description;
    }
}
