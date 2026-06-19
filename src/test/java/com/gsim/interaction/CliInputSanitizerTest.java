package com.gsim.interaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CliInputSanitizer")
class CliInputSanitizerTest {

    @Test
    @DisplayName("null 返回空字符串")
    void nullReturnsEmpty() {
        assertEquals("", CliInputSanitizer.sanitize(null));
    }

    @Test
    @DisplayName("正常中文不破坏")
    void preservesChinese() {
        String input = "你好世界，这是一条测试消息";
        assertEquals(input, CliInputSanitizer.sanitize(input));
    }

    @Test
    @DisplayName("正常英文标点不破坏")
    void preservesEnglishAndPunctuation() {
        String input = "Hello, World! This is a test. With punctuation? Yes.";
        assertEquals(input, CliInputSanitizer.sanitize(input));
    }

    @Test
    @DisplayName("移除 ANSI escape sequences")
    void removesAnsiEscape() {
        String input = "[32mGreen text[0m normal text";
        String result = CliInputSanitizer.sanitize(input);
        assertTrue(result.contains("Green text"));
        assertTrue(result.contains("normal text"));
        assertFalse(result.contains("["));
    }

    @Test
    @DisplayName("移除 ASCII 控制字符")
    void removesAsciiControlChars() {
        // 0x00-0x08, 0x0B-0x0C, 0x0E-0x1F
        String input = "testtext";
        String result = CliInputSanitizer.sanitize(input);
        assertEquals("testtext", result);
    }

    @Test
    @DisplayName("保留换行和制表符")
    void preservesNewlineAndTab() {
        String input = "line1\nline2\tindented";
        String result = CliInputSanitizer.sanitize(input);
        assertTrue(result.contains("line1"));
        assertTrue(result.contains("line2"));
        assertTrue(result.contains("\tindented"));
    }

    @Test
    @DisplayName("移除 DEL 0x7F")
    void removesDel() {
        String input = "testtext";
        String result = CliInputSanitizer.sanitize(input);
        assertEquals("testtext", result);
    }

    @Test
    @DisplayName("移除 Unicode replacement char U+FFFD")
    void removesUnicodeReplacementChar() {
        String input = "test�to�text";
        String result = CliInputSanitizer.sanitize(input);
        assertEquals("testtotext", result);
    }

    @Test
    @DisplayName("移除方向键残留 ^[[A ^[[B")
    void removesArrowKeyResidue() {
        String input = "some text^[[A more text^[[B";
        String result = CliInputSanitizer.sanitize(input);
        assertTrue(result.contains("some text"));
        assertTrue(result.contains("more text"));
        assertFalse(result.contains("^[[A"));
        assertFalse(result.contains("^[[B"));
    }

    @Test
    @DisplayName("规范化 CRLF")
    void normalizesCrlf() {
        String result = CliInputSanitizer.sanitize("line1\r\nline2\rline3");
        assertEquals("line1\nline2\nline3", result);
    }

    @Test
    @DisplayName("trim 首尾空白")
    void trimsWhitespace() {
        assertEquals("hello", CliInputSanitizer.sanitize("  hello  "));
        assertEquals("hello", CliInputSanitizer.sanitize("\nhello\n"));
    }

    @Test
    @DisplayName("空输入返回空字符串")
    void emptyInputReturnsEmpty() {
        assertEquals("", CliInputSanitizer.sanitize(""));
        assertEquals("", CliInputSanitizer.sanitize("   "));
    }

    @Test
    @DisplayName("混合场景不破坏有效内容")
    void mixedScenarioPreservesValidContent() {
        String input = "[1m玩家A[0m：前往^[[A龙门测试�内容";
        String result = CliInputSanitizer.sanitize(input);
        assertTrue(result.contains("玩家A"));
        assertTrue(result.contains("前往"));
        assertTrue(result.contains("龙门"));
        assertTrue(result.contains("测试"));
        assertTrue(result.contains("内容"));
        assertFalse(result.contains(""));
        assertFalse(result.contains("^[[A"));
        assertFalse(result.contains(""));
        assertFalse(result.contains("�"));
    }
}
