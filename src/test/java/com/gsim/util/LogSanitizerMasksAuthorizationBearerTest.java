package com.gsim.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 LogSanitizer.maskAuthorization 和 maskBearerToken 正确脱敏
 * HTTP Authorization Header 中的 Bearer token。
 */
@DisplayName("LogSanitizer 脱敏 Authorization Bearer")
class LogSanitizerMasksAuthorizationBearerTest {

    @Test
    @DisplayName("隐藏 Authorization: Bearer <token> 中的 token")
    void maskAuthorizationHidesBearerToken() {
        String input = "Authorization: Bearer sk-abc123def456";
        String result = LogSanitizer.maskAuthorization(input);

        assertTrue(result.contains("Authorization: Bearer ***"),
                "Should keep prefix but replace token: " + result);
        assertFalse(result.contains("sk-abc123def456"),
                "Token should be masked: " + result);
    }

    @Test
    @DisplayName("小写 'bearer' 同样被脱敏")
    void lowercaseBearerTokenMasked() {
        String input = "authorization: bearer xyz-789";
        String result = LogSanitizer.maskAuthorization(input);

        assertTrue(result.contains("authorization: bearer ***"),
                "Case-insensitive match: " + result);
        assertFalse(result.contains("xyz-789"),
                "Token should be masked: " + result);
    }

    @Test
    @DisplayName("多个 Authorization header 在一条日志中全部脱敏")
    void multipleAuthHeadersAllMasked() {
        String input = "Req: Authorization: Bearer tok1, Prev: Authorization: Bearer tok2";
        String result = LogSanitizer.maskAuthorization(input);

        assertDoesNotThrow(() -> {
            assertFalse(result.contains("tok1"), "First token masked");
            assertFalse(result.contains("tok2"), "Second token masked");
        });
    }

    @Test
    @DisplayName("不带 Authorization 前缀的独立 Bearer token 也脱敏")
    void bareBearerTokenMasked() {
        String input = "Token: Bearer sk-proj-12345";
        String result = LogSanitizer.maskBearerToken(input);

        assertEquals("Token: Bearer ***", result,
                "Bare Bearer should be masked: " + result);
    }

    @Test
    @DisplayName("null 输入返回 null（不抛异常）")
    void nullInputReturnsNull() {
        assertNull(LogSanitizer.maskAuthorization(null));
        assertNull(LogSanitizer.maskBearerToken(null));
    }

    @Test
    @DisplayName("不含敏感信息的文本原样返回")
    void cleanTextPreserved() {
        String input = "GET /api/status HTTP/1.1 200 OK";
        String result = LogSanitizer.maskAuthorization(input);

        assertEquals(input, result,
                "Clean text should be unchanged");
    }
}
