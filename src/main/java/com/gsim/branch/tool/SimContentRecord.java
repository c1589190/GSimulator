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
        String metadata,   // optional JSON string
        String revisionOf  // 重推时指向旧 simId；首次创建为空字符串
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
                metadata != null ? metadata : "",
                ""
        );
    }

    /** 创建一条 revision（重推）记录，revisionOf 指向旧 simId。 */
    public static SimContentRecord createRevision(String simId, String branchId,
                                                  String type, String title, String content,
                                                  String summary, String status, String source,
                                                  String metadata, String revisionOf) {
        return new SimContentRecord(
                simId, branchId,
                type != null ? type : "other",
                title != null ? title : "",
                content != null ? content : "",
                summary != null ? summary : "",
                status != null ? status : "draft",
                source != null ? source : "agent",
                Instant.now().toString(),
                metadata != null ? metadata : "",
                revisionOf != null ? revisionOf : ""
        );
    }
}
