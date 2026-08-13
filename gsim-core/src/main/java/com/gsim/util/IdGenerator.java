package com.gsim.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一 ID 生成器。
 * 使用 UUID v4 并截取短前缀以保证可读性。
 */
public final class IdGenerator {

    private IdGenerator() {
        // utility class
    }

    private static final AtomicInteger nodeCounter = new AtomicInteger(0);

    /**
     * 生成节点 ID，格式如 "n0000"、 "n0001"。
     *
     * @return 节点 ID 字符串
     */
    public static String nodeId() {
        return String.format("n%04d", nodeCounter.getAndIncrement());
    }

    /**
     * 重置节点计数器为 0。
     */
    public static void resetNodeCounter() {
        nodeCounter.set(0);
    }

    /**
     * 从已有节点 ID 种下计数器（查找最大 nXXXX，设为 max+1）。
     *
     * @param value 要设置的计数器值
     */
    public static void seedNodeCounter(int value) {
        nodeCounter.set(value);
    }

    /**
     * 生成短 ID，格式如 "task-a1b2c3d4"。
     *
     * @param prefix ID 前缀（如 "task"、"campaign"）
     * @return 生成的 ID 字符串
     */
    public static String generate(String prefix) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String shortId = uuid.substring(0, 8);
        return prefix + "-" + shortId;
    }

    /**
     * 生成 campaign ID，格式如 "campaign-a1b2c3d4"。
     *
     * @return campaign ID
     */
    public static String campaignId() {
        return generate("campaign");
    }

    /**
     * 生成 turn ID（按序号），格式如 "turn-001"。
     *
     * @param index 回合序号
     * @return turn ID
     */
    public static String turnId(int index) {
        return String.format("turn-%03d", index);
    }

    /**
     * 生成 player action ID，格式如 "action-a1b2c3d4"。
     *
     * @return player action ID
     */
    public static String playerActionId() {
        return generate("action");
    }

    /**
     * 生成 task ID，格式如 "task-a1b2c3d4"。
     *
     * @return task ID
     */
    public static String taskId() {
        return generate("task");
    }

    /**
     * 生成 timeline event ID，格式如 "tlevt-a1b2c3d4"。
     *
     * @return timeline event ID
     */
    public static String timelineEventId() {
        return generate("tlevt");
    }

    /**
     * 生成 state change ID，格式如 "stchg-a1b2c3d4"。
     *
     * @return state change ID
     */
    public static String stateChangeId() {
        return generate("stchg");
    }

    /**
     * 生成 research document ID，格式如 "rdoc-a1b2c3d4"。
     *
     * @return research document ID
     */
    public static String researchDocId() {
        return generate("rdoc");
    }

    /**
     * 生成 evidence item ID，格式如 "evid-a1b2c3d4"。
     *
     * @return evidence item ID
     */
    public static String evidenceId() {
        return generate("evid");
    }

    // ---- Knowledge Store IDs ----

    /**
     * 生成 knowledge document ID，格式如 "kdoc-a1b2c3d4"。
     *
     * @return knowledge document ID
     */
    public static String knowledgeDocId() {
        return generate("kdoc");
    }

    /**
     * 生成 knowledge chunk ID，格式如 "kchu-a1b2c3d4"。
     *
     * @return knowledge chunk ID
     */
    public static String knowledgeChunkId() {
        return generate("kchu");
    }

    /**
     * 生成 embedding profile ID，格式如 "epro-a1b2c3d4"。
     *
     * @return embedding profile ID
     */
    public static String embeddingProfileId() {
        return generate("epro");
    }
}
