package com.gsim.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LlmConfig.toProviderConfig(timeoutSeconds) 治理测试 —
 * 超时不再硬编码 120，由调用方从配置（llm.timeout_seconds）传入。
 */
@DisplayName("LlmConfig toProviderConfig 超时可配")
class LlmConfigTimeoutTest {

    @Test
    @DisplayName("toProviderConfig(60) 产出的 ProviderConfig timeoutSeconds == 60")
    void customTimeoutPropagates() {
        LlmConfig cfg = sampleConfig();
        ProviderConfig pc = cfg.toProviderConfig(60);
        assertEquals(60, pc.timeoutSeconds(), "超时参数必须透传到 ProviderConfig");
    }

    @Test
    @DisplayName("toProviderConfig(120) 默认路径保持原默认值")
    void defaultTimeoutPath() {
        LlmConfig cfg = sampleConfig();
        ProviderConfig pc = cfg.toProviderConfig(120);
        assertEquals(120, pc.timeoutSeconds(), "默认 120s 语义不变");
    }

    @Test
    @DisplayName("其余 ProviderConfig 字段不受超时参数影响")
    void otherFieldsUntouched() {
        LlmConfig cfg = sampleConfig();
        ProviderConfig pc = cfg.toProviderConfig(60);
        assertEquals("Test Provider", pc.name());
        assertEquals("https://api.example.com/v1", pc.baseUrl());
        assertEquals("secret-key", pc.apiKey());
        assertEquals("test-model", pc.model());
        assertEquals(0.5, pc.temperature());
        assertTrue(pc.supportsForcedToolChoice());
    }

    private static LlmConfig sampleConfig() {
        return new LlmConfig(
                "test",
                "Test Provider",
                "https://api.example.com/v1",
                "secret-key",
                "test-model",
                0.5,
                4096,
                Map.of("extra", "body"),
                null,
                true);
    }
}
