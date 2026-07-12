package com.gsim.worldinfo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonDeserialize
public record NodeSnapshot(
    @JsonProperty("nodeId") String nodeId,
    @JsonProperty("parentId") String parentId,
    @JsonProperty("turn") int turn,
    @JsonProperty("worldTime") String worldTime,
    @JsonProperty("status") String status,
    @JsonProperty("createdAt") String createdAt,
    @JsonProperty("checkpoints") Map<String, Checkpoint> checkpoints
) {
    public NodeSnapshot {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId required");
        if (checkpoints == null) checkpoints = new LinkedHashMap<>();
    }

    /** The root node has no parent. */
    public boolean isRoot() {
        return parentId == null || parentId.isBlank();
    }

    public Checkpoint checkpoint(String id) {
        return checkpoints.get(id);
    }
}
