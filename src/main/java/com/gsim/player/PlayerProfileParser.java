package com.gsim.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 players.md 文件内容为 PlayerProfile 列表。
 *
 * 格式要求：
 * - 一级标题 # 玩家档案
 * - 二级标题 ## 玩家名 开始一个玩家段
 * - 每个玩家段内使用 * 字段名：字段值 格式
 */
public final class PlayerProfileParser {

    private PlayerProfileParser() {}

    /** 从 players.md 正文解析所有玩家档案。 */
    public static List<PlayerProfile> parse(String raw) {
        List<PlayerProfile> profiles = new ArrayList<>();
        if (raw == null || raw.isBlank()) return profiles;

        // 按 ## 切分玩家段
        String[] sections = raw.split("\n## ");
        for (int i = 0; i < sections.length; i++) {
            String section = sections[i].trim();
            if (section.isEmpty()) continue;

            // 第一个 section 是标题和介绍，跳过
            if (i == 0) {
                // 如果第一个 section 也以 ## 开头（raw 开头就是 ##），解析它
                if (section.startsWith("## ")) {
                    PlayerProfile profile = parseSection(section);
                    if (profile != null) profiles.add(profile);
                }
                continue;
            }

            // sections[i] 以 "玩家名\n..." 开头（## 已被 split 吃掉）
            PlayerProfile profile = parseSection("## " + section);
            if (profile != null) profiles.add(profile);
        }

        return profiles;
    }

    /** 解析单个玩家段。 */
    static PlayerProfile parseSection(String section) {
        String name = extractHeading(section);
        if (name == null || name.isEmpty()) return null;

        Map<String, String> fields = new LinkedHashMap<>();

        // 逐字段解析：每个 * key：value 段落从当前行到下一个 * 行或段尾
        String[] lines = section.split("\n");
        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            // 跳过标题行 (## 玩家名)
            if (trimmed.startsWith("## ")) continue;

            // 匹配 * key：value 模式
            if (trimmed.startsWith("* ")) {
                // 保存前一个字段
                if (currentKey != null) {
                    fields.put(currentKey, currentValue.toString().trim());
                }
                // 提取新字段
                int colonIdx = indexOfColon(trimmed);
                if (colonIdx > 0) {
                    currentKey = trimmed.substring(2, colonIdx).trim();
                    currentValue = new StringBuilder();
                    String afterColon = trimmed.substring(colonIdx + 1).trim();
                    if (!afterColon.isEmpty()) {
                        currentValue.append(afterColon);
                    }
                } else {
                    // 没有冒号的行（如 "  * sub：subvalue"），追加到当前值
                    if (currentKey != null) {
                        if (!currentValue.isEmpty()) currentValue.append("\n");
                        currentValue.append(trimmed);
                    }
                }
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                // 续行（关系子条目、多行备注等），追加到当前字段值
                if (currentKey != null) {
                    if (!currentValue.isEmpty()) currentValue.append("\n");
                    currentValue.append(trimmed);
                }
            }
        }
        // 保存最后一个字段
        if (currentKey != null) {
            fields.put(currentKey, currentValue.toString().trim());
        }

        // 解析关系子条目
        String relationships = extractRelationships(section);

        return new PlayerProfile(
                name,
                fields.getOrDefault("类型", PlayerProfile.UNSET),
                fields.getOrDefault("阵营", PlayerProfile.UNSET),
                fields.getOrDefault("身份", PlayerProfile.UNSET),
                fields.getOrDefault("控制资源", PlayerProfile.UNSET),
                fields.getOrDefault("公开目标", PlayerProfile.UNSET),
                fields.getOrDefault("隐藏倾向", PlayerProfile.UNSET),
                fields.getOrDefault("当前状态", PlayerProfile.UNSET),
                relationships,
                fields.getOrDefault("备注", "无"),
                section
        );
    }

    /** 找到 key：value 中冒号的位置，支持全角和半角。 */
    private static int indexOfColon(String line) {
        int chinese = line.indexOf('：');
        int english = line.indexOf(':');
        if (chinese < 0) return english;
        if (english < 0) return chinese;
        return Math.min(chinese, english);
    }

    /** 从 ## 标题提取玩家名。 */
    private static String extractHeading(String section) {
        int nl = section.indexOf('\n');
        String firstLine = nl >= 0 ? section.substring(0, nl) : section;
        if (firstLine.startsWith("## ")) {
            return firstLine.substring(3).trim();
        }
        return null;
    }

    /** 提取关系子条目（* 缩进的条目，跟在"关系："之后）。 */
    private static String extractRelationships(String section) {
        int relIdx = section.indexOf("* 关系：");
        if (relIdx < 0) relIdx = section.indexOf("* 关系:");
        if (relIdx < 0) return PlayerProfile.UNSET;

        // 找到关系字段值的开始
        int valStart = section.indexOf('\n', relIdx);
        if (valStart < 0) return PlayerProfile.UNSET;
        int nextField = section.indexOf("\n* ", valStart + 1);
        if (nextField < 0) nextField = section.length();

        String relSection = section.substring(valStart + 1, nextField).trim();
        if (relSection.isEmpty() || PlayerProfile.UNSET.equals(relSection)) return PlayerProfile.UNSET;

        return relSection;
    }
}
