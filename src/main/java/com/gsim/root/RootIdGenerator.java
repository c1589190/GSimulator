package com.gsim.root;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 生成 ASCII-only rootId。
 * 不把中文放入 rootId。需要中文标题写入 world.md 的 # 世界名称。
 */
public final class RootIdGenerator {

    private RootIdGenerator() {}

    /**
     * 从用户输入生成 rootId: root.<hash8>
     * hash 基于 SHA-256 前 8 位 hex。
     */
    public static String generateFromContent(String content) {
        String toHash = content != null ? content : String.valueOf(System.nanoTime());
        String hash = sha256Hex8(toHash);
        return "root." + hash;
    }

    /** 校验 rootId 是否符合 ASCII slug 规则。 */
    public static boolean isValidRootId(String rootId) {
        return rootId != null && rootId.matches("[a-zA-Z0-9._-]+");
    }

    /** 从用户提供的 rootId 清洗或生成。如果无效，从 content 生成。 */
    public static String resolve(String explicitRootId, String content) {
        if (explicitRootId != null && !explicitRootId.isBlank() && isValidRootId(explicitRootId)) {
            return explicitRootId;
        }
        return generateFromContent(content);
    }

    /** 生成结构化的 world.md 内容。 */
    public static String buildWorldMarkdown(String title, String initialContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 世界观\n\n");
        sb.append("## 世界名称\n\n");
        sb.append(title != null && !title.isBlank() ? title : "未命名世界").append("\n\n");
        sb.append("## 起始时间\n\n");
        sb.append("待设定\n\n");
        sb.append("## 初始设定\n\n");
        sb.append(initialContent).append("\n\n");
        sb.append("## 资料状态\n\n");
        sb.append("- 世界观资料：待导入\n");
        sb.append("- 人物卡：待录入\n");
        sb.append("- 玩家资料：待录入\n");
        return sb.toString();
    }

    /** 从内容提取简短标题（取第一句，最多 50 字符）。 */
    public static String extractTitle(String content) {
        if (content == null || content.isBlank()) return "未命名世界";
        // 取第一句或前 50 字符
        String clean = TextSanitizer.safeStrip(content).trim();
        int end = clean.length();
        for (char c : new char[]{'。', '，', '\n', '；', '.'}) {
            int pos = clean.indexOf(c);
            if (pos > 0 && pos < end) end = pos;
        }
        String title = clean.substring(0, Math.min(end, 50)).trim();
        return title.isEmpty() ? "未命名世界" : title;
    }

    private static String sha256Hex8(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) { // 前 4 字节 = 8 hex 字符
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Fallback: use hashCode
            return String.format("%08x", input.hashCode());
        }
    }
}
