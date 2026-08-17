package com.gsim.core.embedding;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EmbeddingClient 超时配置注入测试 — 断言构造参数流入 OkHttp 客户端。
 */
@DisplayName("EmbeddingClient 超时配置注入")
class EmbeddingClientTimeoutConfigTest {

    @Test
    @DisplayName("构造参数注入 connect/read/write 超时（秒→毫秒）")
    void customTimeoutsReflectedInHttpClient() {
        var client = new EmbeddingClient("https://example.com/v1", "key", "model", 5, 7, 9);

        assertEquals(5000, client.http.connectTimeoutMillis());
        assertEquals(7000, client.http.readTimeoutMillis());
        assertEquals(9000, client.http.writeTimeoutMillis());
    }

    @Test
    @DisplayName("默认构造保留 30/60/30 秒超时")
    void defaultConstructorKeepsDefaults() {
        var client = new EmbeddingClient("https://example.com/v1", "key", "model");

        assertEquals(30_000, client.http.connectTimeoutMillis());
        assertEquals(60_000, client.http.readTimeoutMillis());
        assertEquals(30_000, client.http.writeTimeoutMillis());
    }
}
