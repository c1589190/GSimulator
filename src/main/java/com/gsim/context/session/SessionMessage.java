package com.gsim.context.session;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * ContextSession 内的消息 — 只存储当前会话段的新增消息。
 *
 * @param id               消息 ID
 * @param contextSessionId 所属 ContextSession
 * @param branchId         所属分支
 * @param role             角色：system / user / assistant / tool
 * @param type             类型：chat_user / chat_response / sim_user / sim_response / tool_call / tool_result / system_note / context_reset
 * @param content          消息内容
 * @param createdAt        创建时间
 * @param metadata         附加元数据
 */
public record SessionMessage(
        @JsonProperty("id") String id,
        @JsonProperty("contextSessionId") String contextSessionId,
        @JsonProperty("branchId") String branchId,
        @JsonProperty("role") String role,
        @JsonProperty("type") String type,
        @JsonProperty("content") String content,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("metadata") Map<String, String> metadata
) {
    public SessionMessage {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (role == null) role = "user";
        if (type == null) type = "chat_user";
        if (content == null) content = "";
        if (createdAt == null) createdAt = Instant.now();
        if (metadata == null) metadata = Map.of();
    }

    public static SessionMessage user(String sessionId, String branchId, String content) {
        return new SessionMessage(
                "msg-" + System.nanoTime(), sessionId, branchId,
                "user", "chat_user", content, Instant.now(), Map.of());
    }

    public static SessionMessage assistant(String sessionId, String branchId, String content) {
        return new SessionMessage(
                "msg-" + System.nanoTime(), sessionId, branchId,
                "assistant", "chat_response", content, Instant.now(), Map.of());
    }

    public static SessionMessage toolCall(String sessionId, String branchId, String toolName, String content) {
        return new SessionMessage(
                "msg-" + System.nanoTime(), sessionId, branchId,
                "tool", "tool_call", content, Instant.now(),
                Map.of("toolName", toolName));
    }

    public static SessionMessage toolResult(String sessionId, String branchId, String toolName, String content) {
        return new SessionMessage(
                "msg-" + System.nanoTime(), sessionId, branchId,
                "tool", "tool_result", content, Instant.now(),
                Map.of("toolName", toolName));
    }

    public static SessionMessage systemNote(String sessionId, String branchId, String content) {
        return new SessionMessage(
                "msg-" + System.nanoTime(), sessionId, branchId,
                "system", "system_note", content, Instant.now(), Map.of());
    }
}
