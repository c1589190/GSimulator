package com.gsim.context.session;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * BaseContext 快照 — 分支概要链 + 硬约束 + 当前节点概要 + memory tools 说明。
 * 不包含完整父链 messages。
 *
 * @param id              快照 ID
 * @param branchId        所属分支
 * @param startNodeId     生成时的活动节点
 * @param createdAt       创建时间
 * @param markdown        渲染后的 Markdown 文本
 * @param approxChars     近似字符数
 * @param includedNodeIds 包含的节点 ID 列表
 * @param includedPinIds  包含的硬约束 ID 列表
 */
public record BaseContextSnapshot(
        @JsonProperty("id") String id,
        @JsonProperty("branchId") String branchId,
        @JsonProperty("startNodeId") String startNodeId,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("markdown") String markdown,
        @JsonProperty("approxChars") int approxChars,
        @JsonProperty("includedNodeIds") List<String> includedNodeIds,
        @JsonProperty("includedPinIds") List<String> includedPinIds
) {
    public BaseContextSnapshot {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (markdown == null) markdown = "";
        if (includedNodeIds == null) includedNodeIds = List.of();
        if (includedPinIds == null) includedPinIds = List.of();
        approxChars = markdown.length();
    }
}
