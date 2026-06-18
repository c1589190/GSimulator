package com.gsim.chat;

import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * ToolPollutionFilter — 检测并过滤 LLM 输出中混入的工具定义污染。
 *
 * 工具定义只允许出现在当前一次 LLM 调用的临时 prompt 里，
 * 不允许被写入 branch 消息历史。当 LLM 输出中回显了 tool
 * definitions（如 Claude Code 的 project-level tool 列表），
 * 此类负责在写入前检测并过滤。
 */
public final class ToolPollutionFilter {

    private ToolPollutionFilter() {}

    // ---- 已知污染特征片段 ----

    /** Claude Code project-level tool：test 工具 */
    static final String POLLUTION_FRAGMENT_1 = "Run tests with the given coverage strategy";

    /** Claude Code project-level tool: test 工具的补充描述 */
    static final String POLLUTION_FRAGMENT_2 = "mvn test with optional coverage";

    /** Claude Code project-level tool: test 工具的用途说明 */
    static final String POLLUTION_FRAGMENT_3 = "Use when asked to run tests, check coverage";

    // ---- 检测规则（不区分大小写） ----

    private static final List<Pattern> POLLUTION_PATTERNS = List.of(
            Pattern.compile(Pattern.quote(POLLUTION_FRAGMENT_1), Pattern.CASE_INSENSITIVE),
            Pattern.compile(Pattern.quote(POLLUTION_FRAGMENT_2), Pattern.CASE_INSENSITIVE),
            Pattern.compile(Pattern.quote(POLLUTION_FRAGMENT_3), Pattern.CASE_INSENSITIVE)
    );

    /**
     * 检测 content 是否包含已知工具定义污染片段。
     *
     * @return true 如果内容疑似工具定义污染
     */
    public static boolean isPolluted(String content) {
        if (content == null || content.isBlank()) return false;
        for (Pattern p : POLLUTION_PATTERNS) {
            if (p.matcher(content).find()) return true;
        }
        return false;
    }

    /**
     * 从多行内容中移除明显污染的行。
     * 保留不包含已知污染片段的行。
     *
     * @return 过滤后的文本；如果完全为空则返回空字符串
     */
    public static String sanitize(String content) {
        if (content == null) return "";
        String[] lines = content.split("\n");
        List<String> clean = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (!isPolluted(line)) {
                clean.add(line);
            }
        }
        if (clean.size() == lines.length) return content; // 无污染，原样返回
        return String.join("\n", clean).trim();
    }

    /**
     * 检测并去重连续重复的工具定义块。
     * 如果 content 中包含多个连续的、以 "* text:" 或 "- text:" 开头的
     * 工具定义项，只保留第一次出现的唯一项，后续重复项替换为占位标记。
     *
     * @return 去重后的文本
     */
    public static String deduplicateToolDefinitions(String content) {
        if (content == null || content.isBlank()) return content;

        // 检查是否含有疑似工具列表的特征
        if (!mayContainToolList(content)) return content;

        String[] lines = content.split("\n");
        StringBuilder result = new StringBuilder();
        java.util.Set<String> seenTools = new java.util.HashSet<>();
        String currentToolLine = null;
        int skipCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            // 检测工具定义行：以 "* " 或 "- " 开头，包含 ": "
            if (isToolDefinitionLine(trimmed)) {
                String key = extractToolKey(trimmed);
                if (key != null && !seenTools.add(key)) {
                    // 重复工具定义，跳过
                    skipCount++;
                    continue;
                }
                if (skipCount > 0) {
                    result.append("[deduplicated ").append(skipCount)
                            .append(" duplicate tool definitions]\n");
                    skipCount = 0;
                }
                result.append(line).append("\n");
            } else {
                if (skipCount > 0) {
                    result.append("[deduplicated ").append(skipCount)
                            .append(" duplicate tool definitions]\n");
                    skipCount = 0;
                }
                result.append(line).append("\n");
            }
        }
        if (skipCount > 0) {
            result.append("[deduplicated ").append(skipCount)
                    .append(" duplicate tool definitions]\n");
        }
        return result.toString().trim();
    }

    /** 粗略判断内容是否可能包含工具列表。 */
    private static boolean mayContainToolList(String content) {
        // 如果内容中 "* " 或 "- " 开头的行超过 3 行，可能包含工具列表
        int count = 0;
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith("* ") || t.startsWith("- ")) {
                count++;
                if (count >= 3) return true;
            }
        }
        return false;
    }

    /** 检测一行是否为工具定义行（如 "* tool_name: description"）。 */
    private static boolean isToolDefinitionLine(String line) {
        return (line.startsWith("* ") || line.startsWith("- "))
                && line.contains(":");
    }

    /** 从工具定义行提取 key（工具名）。 */
    private static String extractToolKey(String line) {
        if (line == null) return null;
        String content = line.startsWith("* ") ? line.substring(2)
                : line.startsWith("- ") ? line.substring(2) : line;
        int colon = content.indexOf(':');
        if (colon < 0) return null;
        return content.substring(0, colon).trim().toLowerCase();
    }
}
