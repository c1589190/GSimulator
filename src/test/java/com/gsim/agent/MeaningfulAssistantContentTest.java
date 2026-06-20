package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.gsim.agent.OrchestratorAgent.isMeaningfulAssistantContent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 isMeaningfulAssistantContent 对各类占位符的正确判断。
 */
@DisplayName("isMeaningfulAssistantContent 无效内容过滤")
class MeaningfulAssistantContentTest {

    // ---- 应判为无效（false）----

    @Test
    @DisplayName("null 参数 → false")
    void nullIsNotMeaningful() {
        assertFalse(isMeaningfulAssistantContent(null));
    }

    @Test
    @DisplayName("\"null\" → false")
    void literalNullIsNotMeaningful() {
        assertFalse(isMeaningfulAssistantContent("null"));
    }

    @Test
    @DisplayName("\"NULL\" → false")
    void literalNULLIsNotMeaningful() {
        assertFalse(isMeaningfulAssistantContent("NULL"));
    }

    @Test
    @DisplayName("\"Null\" → false")
    void literalNullCapitalizedIsNotMeaningful() {
        assertFalse(isMeaningfulAssistantContent("Null"));
    }

    @Test
    @DisplayName("\"undefined\" → false")
    void literalUndefinedIsNotMeaningful() {
        assertFalse(isMeaningfulAssistantContent("undefined"));
    }

    @Test
    @DisplayName("\"UNDEFINED\" → false")
    void literalUNDEFINEDIsNotMeaningful() {
        assertFalse(isMeaningfulAssistantContent("UNDEFINED"));
    }

    @Test
    @DisplayName("\"JsonNull\" → false")
    void literalJsonNullIsNotMeaningful() {
        assertFalse(isMeaningfulAssistantContent("JsonNull"));
    }

    @Test
    @DisplayName("\"{}\" → false")
    void emptyJsonObjectIsNotMeaningful() {
        assertFalse(isMeaningfulAssistantContent("{}"));
    }

    @Test
    @DisplayName("\"[]\" → false")
    void emptyJsonArrayIsNotMeaningful() {
        assertFalse(isMeaningfulAssistantContent("[]"));
    }

    @Test
    @DisplayName("空白字符串 → false")
    void blankStringIsNotMeaningful() {
        assertFalse(isMeaningfulAssistantContent("   "));
    }

    @Test
    @DisplayName("空字符串 → false")
    void emptyStringIsNotMeaningful() {
        assertFalse(isMeaningfulAssistantContent(""));
    }

    // ---- 应判为有效（true）----

    @Test
    @DisplayName("\"这个字段是 null\" → true（含词不误杀）")
    void containsNullWordIsMeaningful() {
        assertTrue(isMeaningfulAssistantContent("这个字段是 null"));
    }

    @Test
    @DisplayName("\"null 值代表未知\" → true")
    void startsWithNullWordIsMeaningful() {
        assertTrue(isMeaningfulAssistantContent("null 值代表未知"));
    }

    @Test
    @DisplayName("\"正常回答\" → true")
    void normalAnswerIsMeaningful() {
        assertTrue(isMeaningfulAssistantContent("正常回答"));
    }

    @Test
    @DisplayName("\"你好，世界\" → true")
    void helloWorldIsMeaningful() {
        assertTrue(isMeaningfulAssistantContent("你好，世界"));
    }

    @Test
    @DisplayName("trim 后有效的 \"  null  \" → false（trim 后是 \"null\"）")
    void trimmedToNullLiteralIsNotMeaningful() {
        assertFalse(isMeaningfulAssistantContent("  null  "));
    }

    @Test
    @DisplayName("trim 后有效的 \"  正常回答  \" → true")
    void trimmedNormalAnswerIsMeaningful() {
        assertTrue(isMeaningfulAssistantContent("  正常回答  "));
    }
}
