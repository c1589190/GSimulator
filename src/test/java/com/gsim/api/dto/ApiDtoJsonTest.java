package com.gsim.api.dto;

import com.gsim.api.JsonBodyParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API DTO JSON 序列化/反序列化测试。
 */
@DisplayName("API DTOs")
class ApiDtoJsonTest {

    @Test
    @DisplayName("CommandRequest 反序列化")
    void shouldDeserializeCommandRequest() throws Exception {
        String json = "{\"sessionId\":\"default\",\"command\":\"/status\"}";
        CommandRequest req = JsonBodyParser.parse(json, CommandRequest.class);
        assertEquals("default", req.sessionId());
        assertEquals("/status", req.command());
    }

    @Test
    @DisplayName("CommandRequest 默认 sessionId")
    void shouldDefaultSessionId() throws Exception {
        String json = "{\"command\":\"/help\"}";
        CommandRequest req = JsonBodyParser.parse(json, CommandRequest.class);
        assertEquals("default", req.sessionId());
        assertEquals("/help", req.command());
    }

    @Test
    @DisplayName("ImportUrlRequest 反序列化")
    void shouldDeserializeImportUrlRequest() throws Exception {
        String json = "{\"url\":\"https://example.com\",\"fetchOnly\":true,\"maxPages\":3,\"depth\":1,\"delayMs\":1000}";
        ImportUrlRequest req = JsonBodyParser.parse(json, ImportUrlRequest.class);
        assertEquals("https://example.com", req.url());
        assertTrue(req.fetchOnly());
        assertEquals(3, req.maxPages());
        assertEquals(1, req.depth());
        assertEquals(1000, req.delayMs());
    }

    @Test
    @DisplayName("ImportUrlRequest 默认值")
    void shouldDefaultImportUrlValues() throws Exception {
        String json = "{\"url\":\"https://example.com\"}";
        ImportUrlRequest req = JsonBodyParser.parse(json, ImportUrlRequest.class);
        assertFalse(req.fetchOnly());
        assertEquals(3, req.maxPages());  // default from spec
        assertEquals(1, req.depth());
        assertEquals(1000, req.delayMs());
    }

}
