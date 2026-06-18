package com.gsim.knowledge.embed;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 单个文本的 embedding 向量。
 *
 * @param values float32 数组
 * @param dimensions 向量维度
 * @param profileId 所属 embedding profile
 */
public record EmbeddingVector(
        float[] values,
        int dimensions,
        @JsonProperty("profile_id") String profileId
) {
    public EmbeddingVector {
        if (values == null) {
            values = new float[0];
        }
        if (dimensions != values.length) {
            throw new IllegalArgumentException(
                    "dimensions " + dimensions + " != values.length " + values.length);
        }
    }

    /**
     * 与另一个向量计算 cosine 相似度。两个向量必须同维度且同 profile。
     */
    public double cosineSimilarity(EmbeddingVector other) {
        if (this.dimensions != other.dimensions) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: " + this.dimensions + " vs " + other.dimensions);
        }
        if (this.dimensions == 0) return 0.0;

        float dot = 0f;
        float normA = 0f;
        float normB = 0f;
        for (int i = 0; i < this.dimensions; i++) {
            dot += this.values[i] * other.values[i];
            normA += this.values[i] * this.values[i];
            normB += other.values[i] * other.values[i];
        }
        if (normA == 0f || normB == 0f) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmbeddingVector that)) return false;
        if (this.dimensions != that.dimensions) return false;
        if (!this.profileId.equals(that.profileId)) return false;
        for (int i = 0; i < this.dimensions; i++) {
            if (Float.compare(this.values[i], that.values[i]) != 0) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = profileId.hashCode();
        result = 31 * result + dimensions;
        for (float v : values) {
            result = 31 * result + Float.floatToIntBits(v);
        }
        return result;
    }
}
