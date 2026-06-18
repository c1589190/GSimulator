package com.gsim.context.session;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 上下文会话 — 一个对话段从哪个节点开始、拥有什么 BaseContext。
 *
 * @param apiSessionId  关联的 API/CLI session ID
 * @param sessionId     会话 ID
 * @param branchId      所属分支
 * @param startNodeId   会话起始节点
 * @param baseContextId BaseContextSnapshot ID
 * @param createdAt     创建时间
 * @param lastActiveAt  最后活跃时间
 * @param status        会话状态
 * @param summary       会话结束时生成的摘要（可为 null）
 */
public record ContextSession(
        @JsonProperty("apiSessionId") String apiSessionId,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("branchId") String branchId,
        @JsonProperty("startNodeId") String startNodeId,
        @JsonProperty("baseContextId") String baseContextId,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("lastActiveAt") Instant lastActiveAt,
        @JsonProperty("status") ContextSessionStatus status,
        @JsonProperty("summary") String summary
) {
    public ContextSession {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (apiSessionId == null || apiSessionId.isBlank()) apiSessionId = "default";
        if (branchId == null) branchId = "";
        if (startNodeId == null) startNodeId = "";
        if (status == null) status = ContextSessionStatus.ACTIVE;
        if (createdAt == null) createdAt = Instant.now();
        if (lastActiveAt == null) lastActiveAt = createdAt;
    }

    public ContextSession withStatus(ContextSessionStatus newStatus) {
        return new ContextSession(apiSessionId, sessionId, branchId, startNodeId, baseContextId,
                createdAt, Instant.now(), newStatus, summary);
    }

    public ContextSession withSummary(String newSummary) {
        return new ContextSession(apiSessionId, sessionId, branchId, startNodeId, baseContextId,
                createdAt, Instant.now(), status, newSummary);
    }

    public ContextSession touch() {
        return new ContextSession(apiSessionId, sessionId, branchId, startNodeId, baseContextId,
                createdAt, Instant.now(), status, summary);
    }

    public boolean isActive() {
        return status == ContextSessionStatus.ACTIVE;
    }
}
