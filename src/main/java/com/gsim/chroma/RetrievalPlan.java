package com.gsim.chroma;

import java.util.List;

/**
 * 检索计划 — KnowledgeRouterAgent 生成的查询方案。
 */
public record RetrievalPlan(
        String reason,
        List<RetrievalQuery> queries,
        boolean needWriteBack,
        String writeBackReason
) {
}
