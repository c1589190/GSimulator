package com.gsim.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/branches/{id}/edges 的请求体。
 */
public record CreateEdgeRequest(
        @JsonProperty("fromNodeId") String fromNodeId,
        @JsonProperty("toNodeId") String toNodeId,
        @JsonProperty("label") String label
) {
    public CreateEdgeRequest {
        if (fromNodeId == null) fromNodeId = "";
        if (toNodeId == null) toNodeId = "";
        if (label == null) label = "";
    }
}
