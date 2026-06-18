package com.gsim.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/branches/{id}/nodes 的请求体。
 */
public record CreateNodeRequest(
        @JsonProperty("label") String label,
        @JsonProperty("type") String type,
        @JsonProperty("data") java.util.Map<String, Object> data
) {
    public CreateNodeRequest {
        if (label == null || label.isBlank()) label = "Node";
        if (type == null || type.isBlank()) type = "default";
        if (data == null) data = java.util.Map.of();
    }
}
