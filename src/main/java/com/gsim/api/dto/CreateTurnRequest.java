package com.gsim.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/campaigns/{id}/turns 的请求体。
 */
public record CreateTurnRequest(
        @JsonProperty("index") int index
) {
    public CreateTurnRequest {
        if (index < 0) index = 0;
    }
}
