package com.gsim.context.memory;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 硬约束 — 在 BaseContext 中始终渲染的关键规则/约束。
 *
 * @param id           约束 ID
 * @param branchId     所属分支
 * @param text         约束文本
 * @param sourceNodeId 来源节点
 * @param createdAt    创建时间
 * @param createdBy    创建者
 */
public record PinnedConstraint(
        @JsonProperty("id") String id,
        @JsonProperty("branchId") String branchId,
        @JsonProperty("text") String text,
        @JsonProperty("sourceNodeId") String sourceNodeId,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("createdBy") String createdBy
) {
    public PinnedConstraint {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text is required");
        if (branchId == null) branchId = "";
        if (createdAt == null) createdAt = Instant.now();
        if (createdBy == null) createdBy = "user";
    }
}
