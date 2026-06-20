package com.gsim.agent;

import java.time.Instant;

/**
 * LLM 单次流式输出的可变状态。
 * 所有修改方法使用 synchronized 保证线程安全。
 *
 * <p>不对外暴露实例 — 外部只通过 {@link LlmStreamSnapshot} 读取。
 */
public class LlmStreamState {

    private final String streamId;
    private final StringBuilder reasoning = new StringBuilder();
    private final StringBuilder content = new StringBuilder();
    private int reasoningDeltaCount;
    private int contentDeltaCount;
    private int toolCallDeltaCount;
    private volatile boolean active = true;
    private volatile boolean completed;
    private volatile String error;
    private final Instant startedAt = Instant.now();
    private volatile Instant lastUpdatedAt = Instant.now();

    LlmStreamState(String streamId) {
        this.streamId = streamId;
    }

    // ---- 写入（synchronized） ----

    synchronized void appendReasoning(String delta) {
        if (delta == null || delta.isEmpty()) return;
        reasoning.append(delta);
        reasoningDeltaCount++;
        touch();
    }

    synchronized void appendContent(String delta) {
        if (delta == null || delta.isEmpty()) return;
        content.append(delta);
        contentDeltaCount++;
        touch();
    }

    synchronized void incrementToolCallDelta() {
        toolCallDeltaCount++;
        touch();
    }

    synchronized void complete() {
        this.completed = true;
        this.active = false;
        touch();
    }

    synchronized void fail(String errorMessage) {
        this.error = errorMessage;
        this.completed = true;
        this.active = false;
        touch();
    }

    private void touch() {
        this.lastUpdatedAt = Instant.now();
    }

    // ---- 快照 ----

    synchronized LlmStreamSnapshot snapshot() {
        return new LlmStreamSnapshot(
                streamId,
                reasoning.toString(),
                content.toString(),
                reasoningDeltaCount,
                contentDeltaCount,
                toolCallDeltaCount,
                active,
                completed,
                error
        );
    }

    // ---- getters ----

    String streamId() { return streamId; }
    boolean isActive() { return active; }
    boolean isCompleted() { return completed; }
    Instant startedAt() { return startedAt; }
    Instant lastUpdatedAt() { return lastUpdatedAt; }
}
