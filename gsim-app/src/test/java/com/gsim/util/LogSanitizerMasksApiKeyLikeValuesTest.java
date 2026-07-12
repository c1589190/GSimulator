package com.gsim.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 LogSanitizer.maskApiKey 和 maskSecrets 正确脱敏 API Key
 * 在常见文本格式中的出现。
 */
@DisplayName("LogSanitizer 脱敏 API Key")
class LogSanitizerMasksApiKeyLikeValuesTest {

    @Test
    @DisplayName("api_key=<value> 格式脱敏")
    void apiKeyEqualsFormatMasked() {
        String input = "debug: api_key=sk-secret-12345";
        String result = LogSanitizer.maskApiKey(input);

        assertTrue(result.contains("api_key=***"),
                "Should mask value: " + result);
        assertFalse(result.contains("sk-secret-12345"),
                "Secret should be removed: " + result);
    }

    @Test
    @DisplayName("api-key: <value> 格式脱敏（带短横）")
    void apiKeyColonFormatMasked() {
        String input = "Header: api-key: abc-def-ghi";
        String result = LogSanitizer.maskApiKey(input);

        assertTrue(result.contains("api-key: ***"),
                "Should mask colon format: " + result);
        assertFalse(result.contains("abc-def-ghi"),
                "Secret should be removed: " + result);
    }

    @Test
    @DisplayName("x-api-key header 值脱敏")
    void xApiKeyMasked() {
        String input = "x-api-key: secret-token-789";
        String result = LogSanitizer.maskApiKey(input);

        assertTrue(result.contains("x-api-key: ***"),
                "Should mask x-api-key: " + result);
    }

    @Test
    @DisplayName("JSON 格式 \"apiKey\": \"value\" 脱敏")
    void jsonApiKeyMasked() {
        String input = "{\"apiKey\": \"sk-json-secret\"}";
        String result = LogSanitizer.maskApiKey(input);

        assertTrue(result.contains("\"apiKey\": \"***\""),
                "Should mask JSON apiKey value: " + result);
        assertFalse(result.contains("sk-json-secret"),
                "Secret should be removed: " + result);
    }

    @Test
    @DisplayName("多个 API Key 在同一文本中全部脱敏")
    void multipleApiKeysAllMasked() {
        String input = "config: api_key=val1, header: x-api-key: val2";
        String result = LogSanitizer.maskApiKey(input);

        assertDoesNotThrow(() -> {
            assertFalse(result.contains("val1"), "First API key masked");
            assertFalse(result.contains("val2"), "Second API key masked");
        });
    }

    @Test
    @DisplayName("maskSecrets 一次性脱敏 Authorization + API Key")
    void maskSecretsHandlesBoth() {
        String input = "Auth: Authorization: Bearer sk-tok, Param: api_key=sk-key";
        String result = LogSanitizer.maskSecrets(input);

        assertFalse(result.contains("sk-tok"), "Bearer token masked");
        assertFalse(result.contains("sk-key"), "API key masked");
        assertTrue(result.contains("Bearer ***"), "Bearer prefix preserved");
        assertTrue(result.contains("api_key=***"), "API key prefix preserved");
    }

    @Test
    @DisplayName("null 输入返回 null")
    void nullInputReturnsNull() {
        assertNull(LogSanitizer.maskApiKey(null));
        assertNull(LogSanitizer.maskSecrets(null));
    }

    @Test
    @DisplayName("大小写不敏感匹配")
    void caseInsensitiveMatching() {
        String input1 = "API_KEY=UPPER-CASE-KEY";
        String input2 = "Api-Key: lower-case-key";

        assertFalse(LogSanitizer.maskApiKey(input1).contains("UPPER-CASE-KEY"));
        assertFalse(LogSanitizer.maskApiKey(input2).contains("lower-case-key"));
    }

    @Test
    @DisplayName("不含 Key 的普通文本原样返回")
    void cleanTextPreserved() {
        String input = "Processing turn 3, player action submitted.";
        assertEquals(input, LogSanitizer.maskApiKey(input));
        assertEquals(input, LogSanitizer.maskSecrets(input));
    }
}
