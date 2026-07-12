package com.gsim.interaction;

/**
 * CLI 输入清洗 — 移除 ANSI escape sequences、控制字符、方向键残留等。
 * 不破坏普通中文、英文、标点。
 */
public final class CliInputSanitizer {

    private CliInputSanitizer() {}

    /** 清洗 CLI 原始输入。返回清洗后的字符串，可能为空。 */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) return "";

        String s = raw;

        // 1. 移除 ANSI escape sequences（CSI、OSC 等）
        s = s.replaceAll("\\x1B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~]|\\][^\\x07]*\\x07)", "");

        // 2. 移除 ASCII 控制字符（0x00-0x1F），保留 \n(0x0A), \r(0x0D), \t(0x09)
        s = s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");

        // 3. 移除 DEL (0x7F)
        s = s.replaceAll("\\x7F", "");

        // 4. 移除 Unicode replacement char U+FFFD (�)
        s = s.replaceAll("�", "");

        // 5. 移除孤立 ESC（未形成完整 ANSI 序列）
        s = s.replace("", "");

        // 6. 移除方向键残留序列（^[[A, ^[[B, ^[[C, ^[[D 等）
        s = s.replaceAll("\\^\\[\\[[A-Da-d]", "");
        s = s.replaceAll("\\^\\[\\[[0-9;]*[A-Da-d]", "");
        // 也处理原始的 ESC [ 序列（已在步骤1处理大部分，这里是残留）
        s = s.replaceAll("\\[\\[A-Da-d]", "");

        // 7. 规范化 CRLF → LF
        s = s.replace("\r\n", "\n").replace("\r", "\n");

        // 8. 移除多余空行（保留最多一个连续空行）
        s = s.replaceAll("\n{3,}", "\n\n");

        // 9. trim 首尾空白
        s = s.trim();

        return s;
    }
}
