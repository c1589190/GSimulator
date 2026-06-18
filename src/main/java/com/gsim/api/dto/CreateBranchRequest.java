package com.gsim.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/branches 的请求体。
 */
public record CreateBranchRequest(
        @JsonProperty("name") String name,
        @JsonProperty("parentBranchId") String parentBranchId,
        @JsonProperty("description") String description
) {
    public CreateBranchRequest {
        if (name == null || name.isBlank()) name = "New Branch";
        if (description == null) description = "";
    }
}
