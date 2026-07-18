package com.gsim.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * API 错误信息。
 *
 * <p>封装错误消息和错误码，用于 API 统一错误响应。
 *
 * @param message 错误描述信息
 * @param code    错误码，如 "UNKNOWN"、"NOT_IMPLEMENTED" 等
 */
public record ApiError(@JsonProperty("message") String message, @JsonProperty("code") String code) {
    public ApiError {
        if (message == null || message.isBlank()) message = "Unknown error";
        if (code == null || code.isBlank()) code = "UNKNOWN";
    }

    /**
     * 创建仅包含错误消息的错误信息。
     *
     * @param message 错误描述
     * @return ApiError 实例（错误码默认为 "UNKNOWN"）
     */
    public static ApiError of(String message) {
        return new ApiError(message, "UNKNOWN");
    }

    /**
     * 创建包含错误消息和错误码的错误信息。
     *
     * @param message 错误描述
     * @param code    错误码
     * @return ApiError 实例
     */
    public static ApiError of(String message, String code) {
        return new ApiError(message, code);
    }
}
