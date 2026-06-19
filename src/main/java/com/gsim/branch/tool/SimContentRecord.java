package com.gsim.branch.tool;

import java.time.Instant;

/**
 * 单条推演内容记录 — 保存在 branch 节点文件中。
 */
public record SimContentRecord(
        String simId,
        String branchId,
        String type,       // prologue | scene | event | dialogue | battle | policy | economy | investigation | settlement_draft | other
        String title,
        String content,
        String summary,
        String status,     // draft | active | superseded
        String source,     // agent | user
        String createdAt,
        String metadata    // optional JSON string
) {
    public static SimContentRecord create(String simId, String branchId,
                                          String type, String title, String content,
                                          String summary, String status, String source,
                                          String metadata) {
        return new SimContentRecord(
                simId, branchId,
                type != null ? type : "other",
                title != null ? title : "",
                content != null ? content : "",
                summary != null ? summary : "",
                status != null ? status : "draft",
                source != null ? source : "agent",
                Instant.now().toString(),
                metadata != null ? metadata : ""
        );
    }
}
