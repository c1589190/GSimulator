package com.gsim.util;

/**
 * 日志脱敏工具 — 防止 API Key / Authorization Header 等敏感信息写入日志文件。
 *
 * <p>用法：
 * <pre>{@code
 *   log.debug("Sending request: {}", LogSanitizer.maskSecrets(requestBody));
 *   log.debug("Response headers: {}", LogSanitizer.maskAuthorization(headers));
 * }</pre>
 *
 * <p>注意：这是一个尽力而为（best-effort）工具。对于高度结构化的数据，
 * 建议在业务代码中手动剔除敏感字段后再传入日志。
 */
public final class LogSanitizer {

    private LogSanitizer() {
        // 工具类，禁止实例化
    }

    /** 常用的 Bearer token 模式 */
    private static final java.util.regex.Pattern AUTH_BEARER = java.util.regex.Pattern.compile(
            "(?i)(Authorization\\s*:\\s*Bearer\\s+)\\S+");

    /** API Key 常见 key=value / key: value 模式 */
    private static final java.util.regex.Pattern API_KEY_PAIR = java.util.regex.Pattern.compile(
            "(?i)(api[_-]?key\\s*[:=]\\s*['\"\\s]?)\\S+");

    /** JSON "apiKey": "..." 模式 */
    private static final java.util.regex.Pattern API_KEY_JSON = java.util.regex.Pattern.compile(
            "(\"apiKey\"\\s*:\\s*\")[^\"]+");

    /** HTTP Header Bearer <token> 模式（无 "Authorization:" 前缀，仅 token 本身） */
    private static final java.util.regex.Pattern BEARER_TOKEN = java.util.regex.Pattern.compile(
            "(?i)(Bearer\\s+)\\S+");

    // ---- 公开方法 ----

    /**
     * 脱敏 Authorization HTTP Header 中的 Bearer token。
     *
     * <pre>{@code
     *   maskAuthorization("Authorization: Bearer sk-abc123")  → "Authorization: Bearer ***"
     *   maskAuthorization("Authorization: bearer xyz789")     → "Authorization: bearer ***"
     * }</pre>
     *
     * @param text 原始文本，可为 null
     * @return 脱敏后的文本（null 输入返回 null）
     */
    public static String maskAuthorization(String text) {
        if (text == null) {
            return null;
        }
        return AUTH_BEARER.matcher(text).replaceAll("$1***");
    }

    /**
     * 脱敏常见的 API Key 文本模式，包括：
     * <ul>
     *   <li>{@code api_key=sk-12345}</li>
     *   <li>{@code api-key: abc-def}</li>
     *   <li>{@code x-api-key: value}</li>
     *   <li>{@code "apiKey": "sk-67890"}</li>
     * </ul>
     *
     * @param text 原始文本，可为 null
     * @return 脱敏后的文本（null 输入返回 null）
     */
    public static String maskApiKey(String text) {
        if (text == null) {
            return null;
        }
        String result = API_KEY_PAIR.matcher(text).replaceAll("$1***");
        result = API_KEY_JSON.matcher(result).replaceAll("$1***");
        return result;
    }

    /**
     * 脱敏孤立的 Bearer token（不带 "Authorization:" 前缀的）。
     *
     * <pre>{@code
     *   maskBearerToken("Bearer sk-abc123")  → "Bearer ***"
     * }</pre>
     */
    public static String maskBearerToken(String text) {
        if (text == null) {
            return null;
        }
        return BEARER_TOKEN.matcher(text).replaceAll("$1***");
    }

    /**
     * 一次性执行所有脱敏规则（推荐入口）。
     *
     * @param text 原始文本，可为 null
     * @return 脱敏后的文本（null 输入返回 null）
     */
    public static String maskSecrets(String text) {
        if (text == null) {
            return null;
        }
        String result = maskAuthorization(text);
        result = maskApiKey(result);
        result = maskBearerToken(result);
        return result;
    }
}
