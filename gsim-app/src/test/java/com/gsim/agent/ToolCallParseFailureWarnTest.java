package com.gsim.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that tool call parse failures are logged at warn level.
 * (Static method test — verifies behavior of tryParseToolCall.)
 */
class ToolCallParseFailureWarnTest {

    @Test
    void nullTextReturnsNull() {
        assertNull(OrchestratorAgent.tryParseToolCall(null));
    }

    @Test
    void blankTextReturnsNull() {
        assertNull(OrchestratorAgent.tryParseToolCall("   "));
    }

    @Test
    void plainTextReturnsNull() {
        // Natural language — should return null (not a tool call)
        assertNull(OrchestratorAgent.tryParseToolCall("这是普通回复，没有工具调用。"));
    }

    @Test
    void jsonWithoutToolReturnsNull() {
        // JSON-like but missing "tool" field
        assertNull(OrchestratorAgent.tryParseToolCall("{\"args\":{\"key\":\"val\"}}"));
    }

    @Test
    void malformedJsonReturnsNull() {
        // LLM sometimes outputs broken JSON — should return null, not throw
        assertNull(OrchestratorAgent.tryParseToolCall("{\"tool\":\"test\", \"args\": {broken}"));
        assertNull(OrchestratorAgent.tryParseToolCall("{tool: test, args: {}}"));
    }

    @Test
    void validToolCallParsesCorrectly() {
        var result = OrchestratorAgent.tryParseToolCall(
                "{\"tool\":\"wiki_search\",\"args\":{\"query\":\"test\"}}");
        assertNotNull(result);
        assertEquals("wiki_search", result.tool());
        assertEquals("test", result.args().get("query"));
    }

    @Test
    void codeFencedToolCallParsesCorrectly() {
        var result = OrchestratorAgent.tryParseToolCall(
                "```json\n{\"tool\":\"keyword_search\",\"args\":{\"query\":\"hello\"}}\n```");
        assertNotNull(result);
        assertEquals("keyword_search", result.tool());
        assertEquals("hello", result.args().get("query"));
    }
}
