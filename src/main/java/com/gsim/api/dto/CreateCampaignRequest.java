package com.gsim.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/campaigns 的请求体。
 */
public record CreateCampaignRequest(
        @JsonProperty("name") String name
) {
    public CreateCampaignRequest {
        if (name == null || name.isBlank()) name = "New Campaign";
    }
}
