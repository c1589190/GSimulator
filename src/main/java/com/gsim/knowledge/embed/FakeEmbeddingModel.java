package com.gsim.knowledge.embed;

import com.gsim.util.IdGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用 Fake Embedding 模型 — 确定性输出，不作为生产默认。
 * 使用文本 hash 生成固定维度的伪向量，仅供测试语义搜索流程。
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    public static final int FAKE_DIMENSIONS = 128;

    private final EmbeddingProfile profile;

    public FakeEmbeddingModel() {
        String fingerprint = EmbeddingProfileManager.computeFingerprint(
                "fake", "fake-test-model", FAKE_DIMENSIONS, "");
        this.profile = new EmbeddingProfile(
                IdGenerator.embeddingProfileId(),
                "fake", "fake", "fake-test-model", FAKE_DIMENSIONS,
                "cosine", 1, fingerprint, "active", Instant.now().toString());
    }

    @Override
    public EmbeddingVector embed(String text) {
        return new EmbeddingVector(generateFakeVector(text), FAKE_DIMENSIONS, profile.profileId());
    }

    @Override
    public List<EmbeddingVector> embedAll(List<String> texts) {
        List<EmbeddingVector> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }

    @Override
    public EmbeddingProfile profile() {
        return profile;
    }

    /**
     * 基于文本 hash 生成确定性伪向量。
     * 相同文本总是产生相同的向量。
     */
    static float[] generateFakeVector(String text) {
        float[] vec = new float[FAKE_DIMENSIONS];
        int hash = text.hashCode();
        for (int i = 0; i < FAKE_DIMENSIONS; i++) {
            // 使用不同 seed 产生类随机的确定性值
            int h = Integer.rotateLeft(hash, i % 16) ^ (i * 0x9E3779B9);
            vec[i] = (float) (Math.sin(h) * 0.5);
        }
        // 归一化
        float norm = 0f;
        for (float v : vec) norm += v * v;
        if (norm > 0) {
            float invNorm = 1f / (float) Math.sqrt(norm);
            for (int i = 0; i < FAKE_DIMENSIONS; i++) vec[i] *= invNorm;
        }
        return vec;
    }
}
