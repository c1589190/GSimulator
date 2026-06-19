package com.gsim.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * KnowledgeChain — 当前分支视角下的完整知识链。
 * 按 targetKey 聚合原始项和修订项，按 branch 路径顺序拼接 combinedContent。
 */
public record KnowledgeChain(
        @JsonProperty("target_key") String targetKey,
        @JsonProperty("visible_branches") String visibleBranches,
        @JsonProperty("matched_by") String matchedBy,
        List<KnowledgeChainItem> items,
        @JsonProperty("combined_content") String combinedContent
) {
    public record KnowledgeChainItem(
            @JsonProperty("knowledge_id") String knowledgeId,
            @JsonProperty("branch_id") String branchId,
            @JsonProperty("revision_of") String revisionOf,
            @JsonProperty("change_type") String changeType,
            String content) {}
}
