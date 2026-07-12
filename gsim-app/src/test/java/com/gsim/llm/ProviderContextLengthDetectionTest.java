package com.gsim.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 Provider.isContextLengthError() 对各种上下文长度超限错误消息的检测。
 */
class ProviderContextLengthDetectionTest {

    @Test
    void nullMessage_returnsFalse() {
        assertFalse(Provider.isContextLengthError(null));
    }

    @Test
    void emptyMessage_returnsFalse() {
        assertFalse(Provider.isContextLengthError(""));
    }

    @Test
    void normalErrorMessage_returnsFalse() {
        assertFalse(Provider.isContextLengthError("Internal server error"));
        assertFalse(Provider.isContextLengthError("Rate limit exceeded"));
        assertFalse(Provider.isContextLengthError("Invalid API key"));
        assertFalse(Provider.isContextLengthError("Connection timeout"));
    }

    // ---- English patterns ----

    @ParameterizedTest
    @ValueSource(strings = {
            "context_length_exceeded: maximum of 65536 tokens allowed",
            "Error: context length exceeded, please reduce input size",
            "The request exceeds the maximum context length of 128K tokens",
            "Input is too long for the model's context window",
            "Please reduce the length of your messages",
            "Request entity too large (413) — context exceeds limit"
    })
    void englishContextLengthErrors_detected(String message) {
        assertTrue(Provider.isContextLengthError(message),
                "Should detect as context length error: " + message);
    }

    // ---- Chinese patterns ----

    @ParameterizedTest
    @ValueSource(strings = {
            "请求长度超过模型最大上下文限制",
            "上下文长度超出限制，请缩短输入",
            "上下文过长，无法处理请求",
            "超出上下文窗口大小"
    })
    void chineseContextLengthErrors_detected(String message) {
        assertTrue(Provider.isContextLengthError(message),
                "Should detect as context length error: " + message);
    }

    // ---- Combined patterns ----

    @Test
    void http400WithTokenTruncation_detected() {
        assertTrue(Provider.isContextLengthError(
                "HTTP 400: tokens exceed limit, please truncate input"));
    }

    @Test
    void caseInsensitive() {
        assertTrue(Provider.isContextLengthError("CONTEXT_LENGTH exceeded"));
        assertTrue(Provider.isContextLengthError("Maximum Context length REACHED"));
        assertTrue(Provider.isContextLengthError("the message is TOO LONG for this model"));
    }

    @Test
    void partialMatches_notFooled() {
        // "token" alone shouldn't trigger unless combined with 400 + truncat
        assertFalse(Provider.isContextLengthError("Invalid token"));
        // "400" alone shouldn't trigger
        assertFalse(Provider.isContextLengthError("HTTP 400: Bad request format"));
    }
}
