package com.gsim.root;

/**
 * 文本清洗 — 去除 ANSI 转义序列和控制字符。
 */
public final class TextSanitizer {

    private TextSanitizer() {}

    /** 去除 ANSI 转义序列和 ASCII 控制字符（保留换行和制表符）。 */
    public static String stripAnsiAndControlChars(String input) {
        if (input == null || input.isEmpty()) return input;
        // 移除 ANSI escape sequences
        String cleaned = input.replaceAll("\\x1B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~])", "");
        // 移除 ASCII 控制字符（0x00-0x08, 0x0B-0x0C, 0x0E-0x1F），保留 \n(0x0A), \r(0x0D), \t(0x09)
        cleaned = cleaned.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        return cleaned;
    }

    /** 清洗为空字符串的安全版本。 */
    public static String safeStrip(String input) {
        String result = stripAnsiAndControlChars(input);
        return result != null ? result : "";
    }
}
