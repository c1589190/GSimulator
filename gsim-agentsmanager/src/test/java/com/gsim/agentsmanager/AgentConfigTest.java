package com.gsim.agentsmanager;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AgentConfig.toolFilter 解析")
class AgentConfigTest {

    @Test
    @DisplayName("裸字符串 \"toolFilter\":\"none\" → mode == none")
    void bareStringNone() throws Exception {
        AgentConfig cfg = AgentConfig.fromJson("{\"toolFilter\":\"none\"}");
        assertEquals("none", cfg.toolFilter().mode());
        assertTrue(cfg.toolFilter().allow().isEmpty());
        assertTrue(cfg.toolFilter().deny().isEmpty());
    }

    @Test
    @DisplayName("对象 {\"mode\":\"none\"} → mode == none")
    void objectModeNone() throws Exception {
        AgentConfig cfg = AgentConfig.fromJson("{\"toolFilter\":{\"mode\":\"none\"}}");
        assertEquals("none", cfg.toolFilter().mode());
    }

    @Test
    @DisplayName("对象 {\"mode\":\"custom\"} → mode == custom、allow/deny 空")
    void objectModeCustomEmpty() throws Exception {
        AgentConfig cfg = AgentConfig.fromJson("{\"toolFilter\":{\"mode\":\"custom\"}}");
        assertEquals("custom", cfg.toolFilter().mode());
        assertTrue(cfg.toolFilter().allow().isEmpty());
        assertTrue(cfg.toolFilter().deny().isEmpty());
    }

    @Test
    @DisplayName("对象 {\"mode\":\"custom\",\"allow\":[\"query_node\"]} → allow 生效")
    void objectModeCustomAllow() throws Exception {
        AgentConfig cfg = AgentConfig.fromJson("{\"toolFilter\":{\"mode\":\"custom\",\"allow\":[\"query_node\"]}}");
        assertEquals("custom", cfg.toolFilter().mode());
        assertEquals(List.of("query_node"), cfg.toolFilter().allow());
        assertTrue(cfg.toolFilter().deny().isEmpty());
    }

    @Test
    @DisplayName("无 toolFilter → mode == all（默认回归）")
    void missingToolFilterDefaultsAll() throws Exception {
        AgentConfig cfg = AgentConfig.fromJson("{}");
        assertEquals("all", cfg.toolFilter().mode());
        assertTrue(cfg.toolFilter().allow().isEmpty());
        assertTrue(cfg.toolFilter().deny().isEmpty());
    }
}
