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
    @DisplayName("CreateActionRequest 反序列化")
    void shouldDeserializeCreateActionRequest() throws Exception {
        String json = "{\"playerName\":\"张三\",\"content\":\"向北方派出侦察队\"}";
        CreateActionRequest req = JsonBodyParser.parse(json, CreateActionRequest.class);
        assertEquals("张三", req.playerName());
        assertEquals("向北方派出侦察队", req.content());
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

    @Test
    @DisplayName("SearchDbRequest 反序列化")
    void shouldDeserializeSearchDbRequest() throws Exception {
        String json = "{\"query\":\"塔拉第一大陆军\",\"topK\":5}";
        SearchDbRequest req = JsonBodyParser.parse(json, SearchDbRequest.class);
        assertEquals("塔拉第一大陆军", req.query());
        assertEquals(5, req.topK());
    }

    @Test
    @DisplayName("SearchDbRequest 默认 topK")
    void shouldDefaultTopK() throws Exception {
        String json = "{\"query\":\"test\"}";
        SearchDbRequest req = JsonBodyParser.parse(json, SearchDbRequest.class);
        assertEquals(5, req.topK());
    }

    @Test
    @DisplayName("CreateCampaignRequest 反序列化")
    void shouldDeserializeCreateCampaignRequest() throws Exception {
        String json = "{\"name\":\"Test Campaign\"}";
        CreateCampaignRequest req = JsonBodyParser.parse(json, CreateCampaignRequest.class);
        assertEquals("Test Campaign", req.name());
    }

    @Test
    @DisplayName("CreateBranchRequest 反序列化")
    void shouldDeserializeCreateBranchRequest() throws Exception {
        String json = "{\"name\":\"branch-1\",\"parentBranchId\":\"main\",\"description\":\"A test branch\"}";
        CreateBranchRequest req = JsonBodyParser.parse(json, CreateBranchRequest.class);
        assertEquals("branch-1", req.name());
        assertEquals("main", req.parentBranchId());
        assertEquals("A test branch", req.description());
    }

    @Test
    @DisplayName("CreateNodeRequest 反序列化")
    void shouldDeserializeCreateNodeRequest() throws Exception {
        String json = "{\"label\":\"Node A\",\"type\":\"decision\",\"data\":{\"key\":\"value\"}}";
        CreateNodeRequest req = JsonBodyParser.parse(json, CreateNodeRequest.class);
        assertEquals("Node A", req.label());
        assertEquals("decision", req.type());
        assertEquals("value", req.data().get("key"));
    }

    @Test
    @DisplayName("CreateEdgeRequest 反序列化")
    void shouldDeserializeCreateEdgeRequest() throws Exception {
        String json = "{\"fromNodeId\":\"n1\",\"toNodeId\":\"n2\",\"label\":\"connects\"}";
        CreateEdgeRequest req = JsonBodyParser.parse(json, CreateEdgeRequest.class);
        assertEquals("n1", req.fromNodeId());
        assertEquals("n2", req.toNodeId());
        assertEquals("connects", req.label());
    }
}
