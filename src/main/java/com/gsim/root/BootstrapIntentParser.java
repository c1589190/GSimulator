package com.gsim.root;

import java.util.Optional;

/**
 * 解析用户输入是否应触发 empty-data bootstrap。
 *
 * <p>data 严格为空时，任意非空自然语言输入都允许 bootstrap。
 * data 非空时，不允许自动 bootstrap。
 * 明确前缀格式（如"初始化根节点：..."）仍支持，冒号后内容作为 explicit 提示。
 */
public final class BootstrapIntentParser {

    // 旧的初始化前缀（仍支持，用于提取 explicit 提示）
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

    /**
     * Bootstrap 意图。
     *
     * @param shouldBootstrap 是否应触发 bootstrap
     * @param rawRequest      原始用户输入
     * @param sanitizedRequest 清洗后的用户输入
     * @param explicitTitle   用户明确指定的标题（来自前缀格式冒号后内容，或为空）
     * @param explicitRootId  用户明确指定的 rootId（暂不支持，保留字段）
     */
    public record BootstrapIntent(
            boolean shouldBootstrap,
            String rawRequest,
            String sanitizedRequest,
            Optional<String> explicitTitle,
            Optional<String> explicitRootId
    ) {
        public static BootstrapIntent allow(String raw, String sanitized, Optional<String> title) {
            return new BootstrapIntent(true, raw, sanitized, title, Optional.empty());
        }

        public static BootstrapIntent deny() {
            return new BootstrapIntent(false, null, null, Optional.empty(), Optional.empty());
        }
    }

    /**
     * 解析用户输入。
     *
     * @param userInput   原始用户输入
     * @param isDataEmpty dataRoot 是否严格为空
     * @return BootstrapIntent
     */
    public static BootstrapIntent parse(String userInput, boolean isDataEmpty) {
        if (userInput == null || userInput.isBlank()) {
            return BootstrapIntent.deny();
        }

        String cleaned = TextSanitizer.safeStrip(userInput).trim();
        if (cleaned.isEmpty()) {
            return BootstrapIntent.deny();
        }

        if (!isDataEmpty) {
            // data 非空 — 不允许自动 bootstrap
            return BootstrapIntent.deny();
        }

        // data 严格为空 — 检查是否有明确前缀格式
        for (String prefix : INIT_PREFIXES) {
            if (cleaned.length() > prefix.length() && cleaned.startsWith(prefix)) {
                String content = cleaned.substring(prefix.length()).trim();
                if (!content.isBlank()) {
                    return BootstrapIntent.allow(userInput, cleaned, Optional.of(content));
                }
            }
        }

        // 无前缀 — 仍允许 bootstrap，整条消息作为请求
        return BootstrapIntent.allow(userInput, cleaned, Optional.empty());
    }

    /** 快速检查是否有 bootstrap 意图。 */
    public static boolean hasBootstrapIntent(String userInput, boolean isDataEmpty) {
        return parse(userInput, isDataEmpty).shouldBootstrap();
    }

    /** data 非空时拒绝自动 bootstrap 的提示消息。 */
    public static String nonBootstrapHint() {
        return """
                data 目录非空，不能自动创建第一个 root。
                请使用 /root create <rootId> <初始设定>，或清理 data 后重新启动。""";
    }
}
