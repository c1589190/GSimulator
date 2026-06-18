package com.gsim.knowledge.embed;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExternalEmbeddingModel 使用 MockWebServer 测试，不访问真实外网。
 */
@DisplayName("ExternalEmbeddingModel (Mock)")
class ExternalEmbeddingModelMockTest {

    private MockWebServer mockServer;
    private ExternalEmbeddingModel model;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        String baseUrl = "http://" + mockServer.getHostName() + ":" + mockServer.getPort();
        model = new ExternalEmbeddingModel(baseUrl, "test-api-key", "test-model", 3, 10);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("成功请求返回正确向量")
    void successfulEmbedding() throws Exception {
        // 构造响应
        var root = mapper.createObjectNode();
        var dataArr = root.putArray("data");
        var item = dataArr.addObject();
        var embArr = item.putArray("embedding");
        embArr.add(0.1);
        embArr.add(0.2);
        embArr.add(0.3);

        mockServer.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(root))
                .addHeader("Content-Type", "application/json"));

        EmbeddingVector vec = model.embed("test text");
        assertNotNull(vec);
        assertEquals(3, vec.dimensions());
        assertEquals(model.profile().profileId(), vec.profileId());
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, vec.values(), 0.0001f);
    }

    @Test
    @DisplayName("批量嵌入返回正确结果")
    void batchEmbedding() throws Exception {
        var root = mapper.createObjectNode();
        var dataArr = root.putArray("data");
        for (int i = 0; i < 2; i++) {
            var item = dataArr.addObject();
            var embArr = item.putArray("embedding");
            embArr.add(i * 0.1f);
            embArr.add(i * 0.2f);
            embArr.add(i * 0.3f);
        }

        mockServer.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(root))
                .addHeader("Content-Type", "application/json"));

        List<EmbeddingVector> results = model.embedAll(List.of("text1", "text2"));
        assertEquals(2, results.size());
        assertEquals(3, results.get(0).dimensions());
    }

    @Test
    @DisplayName("HTTP 错误返回 EMBEDDING_PROVIDER_UNAVAILABLE")
    void httpError() {
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> model.embed("test"));
        assertTrue(ex.getMessage().contains("EMBEDDING_PROVIDER_UNAVAILABLE"));
    }

    @Test
    @DisplayName("维度不匹配返回 EMBEDDING_DIMENSION_MISMATCH")
    void dimensionMismatch() throws Exception {
        var root = mapper.createObjectNode();
        var dataArr = root.putArray("data");
        var item = dataArr.addObject();
        var embArr = item.putArray("embedding");
        embArr.add(0.1);
        embArr.add(0.2);
        // 只有 2 维，但期望 3 维

        mockServer.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(root))
                .addHeader("Content-Type", "application/json"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> model.embed("test"));
        assertTrue(ex.getMessage().contains("EMBEDDING_DIMENSION_MISMATCH"));
    }

    @Test
    @DisplayName("profile 信息正确")
    void profileInfo() {
        EmbeddingProfile profile = model.profile();
        assertEquals("external", profile.providerType());
        assertEquals("test-model", profile.modelName());
        assertEquals(3, profile.dimensions());
        assertEquals("cosine", profile.distanceMetric());
        assertTrue(profile.isAvailable());
    }
}
