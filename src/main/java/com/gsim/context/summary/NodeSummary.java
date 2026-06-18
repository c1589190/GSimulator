package com.gsim.context.summary;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * 节点摘要 — 每个分支节点的自然语言概要。
 * 进入 BaseContextSnapshot 的 Branch Evolution Summary 部分。
 *
 * @param nodeId           节点/分支 ID
 * @param branchId         分支 ID（通常与 nodeId 相同）
 * @param title            节点标题
 * @param summary          自然语言摘要
 * @param tags             标签
 * @param sourceMessageIds 来源消息 ID 列表
 * @param sourceOutputId   来源输出 ID
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 */
public record NodeSummary(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("branchId") String branchId,
        @JsonProperty("title") String title,
        @JsonProperty("summary") String summary,
        @JsonProperty("tags") List<String> tags,
        @JsonProperty("sourceMessageIds") List<String> sourceMessageIds,
        @JsonProperty("sourceOutputId") String sourceOutputId,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("updatedAt") Instant updatedAt
) {
    public NodeSummary {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId is required");
        if (title == null) title = "";
        if (summary == null) summary = "";
        if (tags == null) tags = List.of();
        if (sourceMessageIds == null) sourceMessageIds = List.of();
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }
}
