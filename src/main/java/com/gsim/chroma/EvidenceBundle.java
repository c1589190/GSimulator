package com.gsim.chroma;

import java.util.List;

/**
 * 证据包 — 一次知识检索的完整结果集。
 */
public record EvidenceBundle(
        String taskId,
        List<EvidenceItem> items,
        String summary
) {
    public int size() {
        return items != null ? items.size() : 0;
    }
}
