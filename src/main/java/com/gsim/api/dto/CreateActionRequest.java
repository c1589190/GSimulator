package com.gsim.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/campaigns/{id}/turns/{turnId}/actions 的请求体。
 */
public record CreateActionRequest(
        @JsonProperty("playerName") String playerName,
        @JsonProperty("content") String content
) {
    public CreateActionRequest {
        if (playerName == null || playerName.isBlank()) playerName = "未知玩家";
        if (content == null) content = "";
    }
}
