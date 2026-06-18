package com.gsim.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolCallExtractorTest {

    @Test
    void pureJsonToolCall() {
        var result = ToolCallExtractor.extractFirstToolCall(
                "{\"tool\":\"root_status\",\"args\":{}}");
        assertNotNull(result);
        assertEquals("root_status", result.tool());
    }

    @Test
    void fencedJsonToolCall() {
        var result = ToolCallExtractor.extractFirstToolCall(
                "```json\n{\"tool\":\"root_status\",\"args\":{}}\n```");
        assertNotNull(result);
        assertEquals("root_status", result.tool());
    }

    @Test
    void mixedChineseTextThenRawJson() {
        var result = ToolCallExtractor.extractFirstToolCall(
                "让我先看看当前状态：\n\n{\"tool\":\"root_status\",\"args\":{}}");
        assertNotNull(result, "Should extract tool call from mixed Chinese + JSON");
        assertEquals("root_status", result.tool());
    }

    @Test
    void mixedTextThenFencedJson() {
        var result = ToolCallExtractor.extractFirstToolCall(
                "我需要读取当前世界观。\n```json\n{\"tool\":\"root_world_get\",\"args\":{}}\n```\n读取后我再继续。");
        assertNotNull(result, "Should extract from mixed text + fenced JSON");
        assertEquals("root_world_get", result.tool());
    }

    @Test
    void textWithJsonMidSentence() {
        var result = ToolCallExtractor.extractFirstToolCall(
                "让我先看看{\"tool\":\"root_status\",\"args\":{}}再决定怎么做。");
        assertNotNull(result);
        assertEquals("root_status", result.tool());
    }

    @Test
    void plainTextNoJsonReturnsNull() {
        assertNull(ToolCallExtractor.extractFirstToolCall("这是普通回复，没有工具调用。"));
        assertNull(ToolCallExtractor.extractFirstToolCall("让我想想应该怎么回答。"));
    }

    @Test
    void nullOrBlankReturnsNull() {
        assertNull(ToolCallExtractor.extractFirstToolCall(null));
        assertNull(ToolCallExtractor.extractFirstToolCall("   "));
    }

    @Test
    void jsonWithoutToolFieldReturnsNull() {
        assertNull(ToolCallExtractor.extractFirstToolCall("{\"args\":{\"key\":\"val\"}}"));
    }

    @Test
    void toolCallWithArgs() {
        var result = ToolCallExtractor.extractFirstToolCall(
                "{\"tool\":\"knowledge_upsert\",\"args\":{\"title\":\"Test\",\"content\":\"Hello\"}}");
        assertNotNull(result);
        assertEquals("knowledge_upsert", result.tool());
        assertEquals("Test", result.args().get("title"));
        assertEquals("Hello", result.args().get("content"));
    }

    @Test
    void twoJsonObjectsExtractsFirstTool() {
        var result = ToolCallExtractor.extractFirstToolCall(
                "First: {\"data\":\"not a tool\"} and then {\"tool\":\"root_status\",\"args\":{}}");
        assertNotNull(result);
        assertEquals("root_status", result.tool());
    }

    @Test
    void malformedJsonReturnsNull() {
        assertNull(ToolCallExtractor.extractFirstToolCall("{\"tool\":\"test\", \"args\": {broken}"));
    }
}
