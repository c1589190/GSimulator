package com.gsim.knowledge.embed;

import java.util.List;

/**
 * Embedding 模型接口 — 将文本转换为向量。
 * 所有 LLM 调用必须通过此接口，不允许业务代码直接拼 HTTP 请求。
 */
public interface EmbeddingModel {

    /** 对单个文本生成 embedding。 */
    EmbeddingVector embed(String text);

    /** 批量生成 embeddings。 */
    List<EmbeddingVector> embedAll(List<String> texts);

    /** 获取此模型对应的 profile 档案。 */
    EmbeddingProfile profile();

    /** 此模型是否可用（如 external API 可达、local 模型文件存在）。 */
    default boolean isAvailable() {
        return profile().isAvailable();
    }
}
