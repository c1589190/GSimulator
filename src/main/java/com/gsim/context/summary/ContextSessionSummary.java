package com.gsim.context.summary;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * ContextSession 结束时生成的会话摘要。
 *
 * @param contextSessionId    所属 ContextSession
 * @param branchId            所属分支
 * @param summary             自然语言摘要
 * @param importantMessageIds 重要消息 ID
 * @param suggestedPins       建议添加的硬约束
 * @param createdAt           创建时间
 */
public record ContextSessionSummary(
        @JsonProperty("contextSessionId") String contextSessionId,
        @JsonProperty("branchId") String branchId,
        @JsonProperty("summary") String summary,
        @JsonProperty("importantMessageIds") List<String> importantMessageIds,
        @JsonProperty("suggestedPins") List<String> suggestedPins,
        @JsonProperty("createdAt") Instant createdAt
) {
    public ContextSessionSummary {
        if (contextSessionId == null || contextSessionId.isBlank())
            throw new IllegalArgumentException("contextSessionId is required");
        if (summary == null) summary = "";
        if (importantMessageIds == null) importantMessageIds = List.of();
        if (suggestedPins == null) suggestedPins = List.of();
        if (createdAt == null) createdAt = Instant.now();
    }
}
