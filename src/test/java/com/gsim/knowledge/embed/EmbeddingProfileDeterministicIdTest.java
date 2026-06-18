package com.gsim.knowledge.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 EmbeddingProfile profileId 确定性生成。
 * 同一配置多次创建得到相同 profileId。
 */
@DisplayName("EmbeddingProfile Deterministic ID")
class EmbeddingProfileDeterministicIdTest {

    @Test
    @DisplayName("同一 external 配置重复创建 → profileId 相同")
    void sameExternalConfigProducesSameProfileId() {
        ExternalEmbeddingModel m1 = new ExternalEmbeddingModel(
                "https://api.example.com/v1", "sk-test", "test-model", 1024, 10);
        ExternalEmbeddingModel m2 = new ExternalEmbeddingModel(
                "https://api.example.com/v1", "sk-test", "test-model", 1024, 10);

        assertEquals(m1.profile().profileId(), m2.profile().profileId(),
                "Same config should produce same profileId");
        assertEquals(m1.profile().configFingerprint(), m2.profile().configFingerprint());
    }

    @Test
    @DisplayName("不同 model → 不同 profileId")
    void differentModelProducesDifferentProfileId() {
        ExternalEmbeddingModel m1 = new ExternalEmbeddingModel(
                "https://api.example.com/v1", "sk-test", "model-a", 1024, 10);
        ExternalEmbeddingModel m2 = new ExternalEmbeddingModel(
                "https://api.example.com/v1", "sk-test", "model-b", 1024, 10);

        assertNotEquals(m1.profile().profileId(), m2.profile().profileId());
    }

    @Test
    @DisplayName("不同 dimensions → 不同 profileId")
    void differentDimensionsProducesDifferentProfileId() {
        ExternalEmbeddingModel m1 = new ExternalEmbeddingModel(
                "https://api.example.com/v1", "sk-test", "test-model", 1024, 10);
        ExternalEmbeddingModel m2 = new ExternalEmbeddingModel(
                "https://api.example.com/v1", "sk-test", "test-model", 1536, 10);

        assertNotEquals(m1.profile().profileId(), m2.profile().profileId());
    }

    @Test
    @DisplayName("不同 baseUrl → 不同 profileId")
    void differentBaseUrlProducesDifferentProfileId() {
        ExternalEmbeddingModel m1 = new ExternalEmbeddingModel(
                "https://api.example.com/v1", "sk-test", "test-model", 1024, 10);
        ExternalEmbeddingModel m2 = new ExternalEmbeddingModel(
                "https://api.other.com/v1", "sk-test", "test-model", 1024, 10);

        assertNotEquals(m1.profile().profileId(), m2.profile().profileId());
    }

    @Test
    @DisplayName("profileId 以 emb_ 开头")
    void profileIdStartsWithEmb() {
        ExternalEmbeddingModel m = new ExternalEmbeddingModel(
                "https://api.example.com/v1", "sk-test", "test-model", 1024, 10);
        assertTrue(m.profile().profileId().startsWith("emb_"),
                "profileId should start with emb_, got: " + m.profile().profileId());
    }

    @Test
    @DisplayName("LocalSmall 同一配置 → 相同 profileId")
    void sameLocalSmallConfigProducesSameProfileId() {
        // 目录不存在，但 profile 仍然生成（状态为 unavailable）
        LocalSmallEmbeddingModel m1 = new LocalSmallEmbeddingModel(
                "/tmp/test-model-dir", "local-model", 384);
        LocalSmallEmbeddingModel m2 = new LocalSmallEmbeddingModel(
                "/tmp/test-model-dir", "local-model", 384);

        assertEquals(m1.profile().profileId(), m2.profile().profileId());
    }

    @Test
    @DisplayName("query vector profileId 与 model profileId 一致")
    void queryVectorProfileIdMatchesModelProfileId() {
        // 使用 FakeEmbeddingModel 测试（不访问外网）
        FakeEmbeddingModel model = new FakeEmbeddingModel();
        EmbeddingVector vec = model.embed("test query");
        assertEquals(model.profile().profileId(), vec.profileId());
    }
}
