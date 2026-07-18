package com.gsim.worldinfo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点快照 -- 世界节点在某一时间点的状态。
 *
 * <p>保存节点的元数据（ID、父节点 ID、回合数、世界时间、状态、创建时间）
 * 以及该节点下的所有 {@link Checkpoint} 和外部应用可附加的自定义数据。
 * 外部应用可通过 {@link #attachments()} 附加任意数据，GSim 不会解释这些
 * 附加数据的含义，仅原样存储和返回。
 *
 * @param nodeId      节点 ID
 * @param parentId    父节点 ID（根节点此值为空）
 * @param turn        回合数
 * @param worldTime   世界时间
 * @param status      节点状态
 * @param createdAt   创建时间
 * @param checkpoints 检查点映射（键为检查点 ID）
 * @param attachments 附加数据映射
 */
@JsonDeserialize
public record NodeSnapshot(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("turn") int turn,
        @JsonProperty("worldTime") String worldTime,
        @JsonProperty("status") String status,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("checkpoints") Map<String, Checkpoint> checkpoints,
        @JsonProperty("attachments") Map<String, Object> attachments) {
    public NodeSnapshot {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId required");
        if (checkpoints == null) checkpoints = new LinkedHashMap<>();
        if (attachments == null) attachments = new LinkedHashMap<>();
    }

    /**
     * 判断当前节点是否为根节点。
     *
     * @return 如果是根节点（无父节点）返回 true，否则返回 false
     */
    public boolean isRoot() {
        return parentId == null || parentId.isBlank();
    }

    /**
     * 根据检查点 ID 获取对应的检查点。
     *
     * @param id 检查点 ID
     * @return 检查点实例，如果不存在则返回 null
     */
    public Checkpoint checkpoint(String id) {
        return checkpoints.get(id);
    }
}
