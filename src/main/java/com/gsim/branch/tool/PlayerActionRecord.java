package com.gsim.branch.tool;

import java.time.Instant;

/**
 * 玩家行动记录 — 保存在 branch 节点文件中，与 input.md 的临时输入不同。
 * 这是永久档案，不会因 clearInput 或下回合创建而消失。
 */
public record PlayerActionRecord(
        String actId,
        String branchId,
        String playerName,
        String content,
        String summary,
        String status,     // active | revised | superseded
        String source,     // agent | user
        String createdAt,
        String revisionOf  // 修订时指向旧 actId；首次创建为空
) {
    public static PlayerActionRecord create(String actId, String branchId,
                                            String playerName, String content,
                                            String summary, String status, String source) {
        return new PlayerActionRecord(
                actId, branchId,
                playerName != null ? playerName : "",
                content != null ? content : "",
                summary != null ? summary : "",
                status != null ? status : "active",
                source != null ? source : "user",
                Instant.now().toString(),
                ""
        );
    }

    /** 创建修订记录（不覆盖旧版）。 */
    public static PlayerActionRecord createRevision(String actId, String branchId,
                                                    String playerName, String content,
                                                    String summary, String status, String source,
                                                    String revisionOf) {
        return new PlayerActionRecord(
                actId, branchId,
                playerName != null ? playerName : "",
                content != null ? content : "",
                summary != null ? summary : "",
                status != null ? status : "active",
                source != null ? source : "user",
                Instant.now().toString(),
                revisionOf != null ? revisionOf : ""
        );
    }
}
