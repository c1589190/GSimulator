package com.gsim.knowledge.embed;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Embedding 模型配置档案 — 记录向量坐标系信息。
 * 不同 embedding 模型生成的向量不能混用。
 */
public record EmbeddingProfile(
        @JsonProperty("profile_id") String profileId,
        @JsonProperty("provider_type") String providerType,
        @JsonProperty("provider_name") String providerName,
        @JsonProperty("model_name") String modelName,
        int dimensions,
        @JsonProperty("distance_metric") String distanceMetric,
        int normalize,
        @JsonProperty("config_fingerprint") String configFingerprint,
        String status,
        @JsonProperty("created_at") String createdAt
) {
    public EmbeddingProfile {
        if (distanceMetric == null || distanceMetric.isBlank()) {
            distanceMetric = "cosine";
        }
    }

    /** 此 profile 是否可用（非 unavailable 状态）。 */
    public boolean isAvailable() {
        return "active".equals(status) || "ok".equals(status);
    }

    /** 创建一个状态为 unavailable 的 profile 副本（如 local-small 模型缺失）。 */
    public EmbeddingProfile withUnavailable() {
        return new EmbeddingProfile(profileId, providerType, providerName, modelName,
                dimensions, distanceMetric, normalize, configFingerprint, "unavailable", createdAt);
    }
}
