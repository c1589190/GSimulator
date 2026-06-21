package com.gsim.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/tasks 和 /api/command 的请求体。
 *
 * <p>新增 autoStart 字段（默认 true，向后兼容）。
 * 设为 false 时任务只创建为 PENDING 状态，需调用 POST /api/tasks/{id}/start 启动。
 */
public record CommandRequest(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("command") String command,
        @JsonProperty("autoStart") Boolean autoStart
) {
    public CommandRequest {
        if (sessionId == null || sessionId.isBlank()) sessionId = "default";
        if (command == null) command = "";
        if (autoStart == null) autoStart = true;
    }

    /**
     * 向后兼容的工厂方法 — 等同于 autoStart=true。
     */
    public static CommandRequest of(String sessionId, String command) {
        return new CommandRequest(sessionId, command, true);
    }
}
