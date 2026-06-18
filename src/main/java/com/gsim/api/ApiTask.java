package com.gsim.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * API 任务记录。
 *
 * @param taskId     任务 ID
 * @param sessionId  所属 session
 * @param command    执行的命令
 * @param status     当前状态
 * @param startedAt  开始时间
 * @param finishedAt 完成时间（未完成时为 null）
 * @param result     执行结果数据
 * @param error      错误信息（成功时为 null）
 */
public record ApiTask(
        @JsonProperty("taskId") String taskId,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("command") String command,
        @JsonProperty("status") ApiTaskStatus status,
        @JsonProperty("startedAt") Instant startedAt,
        @JsonProperty("finishedAt") Instant finishedAt,
        @JsonProperty("result") Map<String, Object> result,
        @JsonProperty("error") String error
) {
    public ApiTask {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        if (sessionId == null || sessionId.isBlank()) sessionId = "default";
        if (command == null) command = "";
        if (status == null) status = ApiTaskStatus.PENDING;
    }

    /**
     * 创建初始 PENDING 状态的任务。
     */
    public static ApiTask createPending(String taskId, String sessionId, String command) {
        return new ApiTask(taskId, sessionId, command,
                ApiTaskStatus.PENDING, Instant.now(), null, null, null);
    }
}
