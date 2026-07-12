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

    /** 已知作品/主题关键词映射。key 为小写匹配词，value 为 rootId 后缀。 */
    private static final java.util.Map<String, String> KNOWN_TOPICS = java.util.Map.ofEntries(
            java.util.Map.entry("明日方舟", "arknights-terra"),
            java.util.Map.entry("arknights", "arknights-terra"),
            java.util.Map.entry("泰拉", "arknights-terra"),
            java.util.Map.entry("罗马尼亚", "romania"),
            java.util.Map.entry("romania", "romania"),
            java.util.Map.entry("东南亚", "sea"),
            java.util.Map.entry("southeast asia", "sea"),
            java.util.Map.entry("乌萨斯", "arknights-ursus"),
            java.util.Map.entry("ursus", "arknights-ursus"),
            java.util.Map.entry("罗德岛", "arknights-rhodes"),
            java.util.Map.entry("rhodes island", "arknights-rhodes"),
            java.util.Map.entry("维多利亚", "arknights-victoria"),
            java.util.Map.entry("victoria", "arknights-victoria"),
            java.util.Map.entry("炎国", "arknights-yen"),
            java.util.Map.entry("龙门", "arknights-lungmen"),
            java.util.Map.entry("lungmen", "arknights-lungmen"),
            java.util.Map.entry("卡西米尔", "arknights-kazimierz"),
            java.util.Map.entry("kazimierz", "arknights-kazimierz"),
            java.util.Map.entry("莱塔尼亚", "arknights-leithanien"),
            java.util.Map.entry("leithanien", "arknights-leithanien"),
            java.util.Map.entry("哥伦比亚", "arknights-columbia"),
            java.util.Map.entry("columbia", "arknights-columbia"),
            java.util.Map.entry("1850", "1850-era"),
            java.util.Map.entry("1876", "1876-era"),
            java.util.Map.entry("架空", "alt-history"),
            java.util.Map.entry("alternative history", "alt-history"),
            java.util.Map.entry("文游", "narrative"),
            java.util.Map.entry("边境", "frontier"),
            java.util.Map.entry("frontier", "frontier"),
            java.util.Map.entry("感染者", "arknights-infected"),
            java.util.Map.entry("infected", "arknights-infected"),
            java.util.Map.entry("源石", "arknights-originium"),
            java.util.Map.entry("originium", "arknights-originium"),
            java.util.Map.entry("天灾", "arknights-catastrophe"),
            java.util.Map.entry("移动城邦", "arknights-mobile-city"),
            java.util.Map.entry("mobile city", "arknights-mobile-city")
    );

    /**
     * 从用户文本中识别主题，生成语义化 rootId。
     * 如果能识别已知作品/主题 → root.&lt;topic&gt;
     * 如果无法识别 → root.&lt;hash8&gt;
     * 保证 ASCII-only。
     */
    public static String suggestRootId(String userText) {
        if (userText == null || userText.isBlank()) {
            return generateFromContent(userText);
        }

        String lower = userText.toLowerCase(java.util.Locale.ROOT);

        // 按匹配长度降序排列，优先匹配更长的关键词。
        // 同等长度时，纯数字 key 排在后面（中文/英文主题优先）。
        var sorted = KNOWN_TOPICS.entrySet().stream()
                .filter(e -> lower.contains(e.getKey().toLowerCase(java.util.Locale.ROOT)))
                .sorted((a, b) -> {
                    int lenCmp = Integer.compare(b.getKey().length(), a.getKey().length());
                    if (lenCmp != 0) return lenCmp;
                    // 同等长度：纯数字 key 排在后面
                    boolean aNumeric = a.getKey().chars().allMatch(Character::isDigit);
                    boolean bNumeric = b.getKey().chars().allMatch(Character::isDigit);
                    if (aNumeric && !bNumeric) return 1;
                    if (!aNumeric && bNumeric) return -1;
                    return 0;
                })
                .toList();

        if (!sorted.isEmpty()) {
            String suffix = sorted.get(0).getValue();
            return "root." + suffix;
        }

        // 无法识别 — 使用 hash
        return generateFromContent(userText);
    }

    /**
     * 生成带冲突避免的 rootId。
     * 如果 baseId 已被占用，追加短 hash。
     */
    public static String suggestRootIdWithCollisionAvoidance(String userText, java.util.Set<String> existingIds) {
        String base = suggestRootId(userText);
        if (existingIds == null || !existingIds.contains(base)) {
            return base;
        }
        // 冲突 — 追加短 hash
        String hash = sha256Hex8(userText != null ? userText : String.valueOf(System.nanoTime()));
        return base + "-" + hash.substring(0, 4);
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
