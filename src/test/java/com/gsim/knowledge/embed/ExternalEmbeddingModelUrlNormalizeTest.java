package com.gsim.knowledge.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ExternalEmbeddingModel URL normalize 逻辑。
 * 不访问外网，只测静态方法。
 */
@DisplayName("ExternalEmbeddingModel URL Normalize")
class ExternalEmbeddingModelUrlNormalizeTest {

    @Test
    @DisplayName("普通 base URL → 拼 /v1/embeddings")
    void plainBaseUrl() {
        assertEquals("https://api.example.com/v1/embeddings",
                ExternalEmbeddingModel.buildEmbeddingsUrl(
                        ExternalEmbeddingModel.normalizeBaseUrl("https://api.example.com")));
    }

    @Test
    @DisplayName("以 /v1 结尾 → 拼 /embeddings")
    void endsWithV1() {
        assertEquals("https://api.example.com/v1/embeddings",
                ExternalEmbeddingModel.buildEmbeddingsUrl(
                        ExternalEmbeddingModel.normalizeBaseUrl("https://api.example.com/v1")));
    }

    @Test
    @DisplayName("以 /v1/ 结尾 → 拼 /embeddings")
    void endsWithV1Slash() {
        assertEquals("https://api.example.com/v1/embeddings",
                ExternalEmbeddingModel.buildEmbeddingsUrl(
                        ExternalEmbeddingModel.normalizeBaseUrl("https://api.example.com/v1/")));
    }

    @Test
    @DisplayName("以 /v1/embeddings 结尾 → 直接使用")
    void endsWithV1Embeddings() {
        assertEquals("https://api.example.com/v1/embeddings",
                ExternalEmbeddingModel.buildEmbeddingsUrl(
                        ExternalEmbeddingModel.normalizeBaseUrl("https://api.example.com/v1/embeddings")));
    }

    @Test
    @DisplayName("带尾部 / 的 /v1/embeddings → 直接使用")
    void endsWithV1EmbeddingsSlash() {
        assertEquals("https://api.example.com/v1/embeddings",
                ExternalEmbeddingModel.buildEmbeddingsUrl(
                        ExternalEmbeddingModel.normalizeBaseUrl("https://api.example.com/v1/embeddings/")));
    }

    @Test
    @DisplayName("baseUrl 为 null → 空")
    void nullBaseUrl() {
        assertEquals("", ExternalEmbeddingModel.normalizeBaseUrl(null));
    }

    @Test
    @DisplayName("baseUrl 为空白 → 空")
    void blankBaseUrl() {
        assertEquals("", ExternalEmbeddingModel.normalizeBaseUrl("   "));
    }

    @Test
    @DisplayName("多个尾部 / 的普通 URL")
    void multipleTrailingSlashes() {
        assertEquals("https://api.example.com/v1/embeddings",
                ExternalEmbeddingModel.buildEmbeddingsUrl(
                        ExternalEmbeddingModel.normalizeBaseUrl("https://api.example.com///")));
    }
}
