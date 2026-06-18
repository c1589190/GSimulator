package com.gsim.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 知识库全局设置 — 对应 knowledge_settings 表的一条记录。
 */
public record KnowledgeSettings(
        String key,
        String value
) {
    /** 内置 key 常量 */
    public static final String KEY_ACTIVE_EMBEDDING_PROFILE_ID = "active_embedding_profile_id";
    public static final String KEY_DEFAULT_COLLECTION = "default_collection";
    public static final String KEY_KNOWLEDGE_STORE_VERSION = "knowledge_store_version";

    public static final String CURRENT_VERSION = "1.0";
}
