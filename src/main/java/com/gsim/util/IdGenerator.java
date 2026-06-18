package com.gsim.util;

import java.util.UUID;

/**
 * 统一 ID 生成器。
 * 使用 UUID v4 并截取短前缀以保证可读性。
 */
public final class IdGenerator {

    private IdGenerator() {
        // utility class
    }

    /**
     * 生成短 ID，格式如 "task-a1b2c3d4"
     */
    public static String generate(String prefix) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String shortId = uuid.substring(0, 8);
        return prefix + "-" + shortId;
    }

    /**
     * 生成 campaign ID
     */
    public static String campaignId() {
        return generate("campaign");
    }

    /**
     * 生成 turn ID（按序号）
     */
    public static String turnId(int index) {
        return String.format("turn-%03d", index);
    }

    /**
     * 生成 player action ID
     */
    public static String playerActionId() {
        return generate("action");
    }

    /**
     * 生成 task ID
     */
    public static String taskId() {
        return generate("task");
    }

    /**
     * 生成 timeline event ID
     */
    public static String timelineEventId() {
        return generate("tlevt");
    }

    /**
     * 生成 state change ID
     */
    public static String stateChangeId() {
        return generate("stchg");
    }

    /**
     * 生成 research document ID
     */
    public static String researchDocId() {
        return generate("rdoc");
    }

    /**
     * 生成 evidence item ID
     */
    public static String evidenceId() {
        return generate("evid");
    }

    // ---- Knowledge Store IDs ----

    /** 生成 knowledge document ID */
    public static String knowledgeDocId() {
        return generate("kdoc");
    }

    /** 生成 knowledge chunk ID */
    public static String knowledgeChunkId() {
        return generate("kchu");
    }

    /** 生成 embedding profile ID */
    public static String embeddingProfileId() {
        return generate("epro");
    }
}
