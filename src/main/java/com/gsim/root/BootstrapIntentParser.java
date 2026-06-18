package com.gsim.root;

/**
 * 解析用户输入是否包含明确的 root bootstrap 意图。
 *
 * <p>只有明确前缀才触发 empty-data bootstrap。
 * 模糊请求、wiki 请求、能力询问不能触发 bootstrap。
 */
public final class BootstrapIntentParser {

    // 允许的初始化前缀（大小写不敏感）
    private static final String[] INIT_PREFIXES = {
            "初始化根节点：",
            "初始化根节点:",
            "初始化世界：",
            "初始化世界:",
            "创建第一个根节点：",
            "创建第一个根节点:",
            "init root:",
            "initialize root:",
    };

    private BootstrapIntentParser() {}

    /** 解析结果 */
    public record ParsedIntent(boolean isBootstrap, String worldContent) {
        public static ParsedIntent notBootstrap() {
            return new ParsedIntent(false, null);
        }
        public static ParsedIntent bootstrap(String content) {
            return new ParsedIntent(true, content);
        }
    }

    /**
     * 检查用户输入是否包含 bootstrap 初始化前缀。
     * 如果匹配，返回 ParsedIntent 并提取前缀后的世界观内容。
     */
    public static ParsedIntent parse(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return ParsedIntent.notBootstrap();
        }

        String cleaned = TextSanitizer.safeStrip(userInput).trim();

        for (String prefix : INIT_PREFIXES) {
            if (cleaned.length() > prefix.length() && cleaned.startsWith(prefix)) {
                String content = cleaned.substring(prefix.length()).trim();
                if (!content.isBlank()) {
                    return ParsedIntent.bootstrap(content);
                }
            }
        }

        return ParsedIntent.notBootstrap();
    }

    /** 快速检查是否有 bootstrap 意图。 */
    public static boolean hasBootstrapIntent(String userInput) {
        return parse(userInput).isBootstrap();
    }

    /** 生成无初始化前缀时的提示消息。 */
    public static String nonBootstrapHint() {
        return """
                当前 data 为空，但这条消息不像可直接写入的初始世界观。
                如果要创建第一个根节点，请使用：

                初始化根节点：<世界观初始设定>

                或：

                /root create <rootId> <世界观初始设定>""";
    }
}
