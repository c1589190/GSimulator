package com.gsim.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/command 和 /api/command/stream 的请求体。
 */
public record CommandRequest(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("command") String command
) {
    public CommandRequest {
        if (sessionId == null || sessionId.isBlank()) sessionId = "default";
        if (command == null) command = "";
    }
}
