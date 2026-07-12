package com.gsim.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * API 错误信息。
 */
public record ApiError(
        @JsonProperty("message") String message,
        @JsonProperty("code") String code
) {
    public ApiError {
        if (message == null || message.isBlank()) message = "Unknown error";
        if (code == null || code.isBlank()) code = "UNKNOWN";
    }

    public static ApiError of(String message) {
        return new ApiError(message, "UNKNOWN");
    }

    public static ApiError of(String message, String code) {
        return new ApiError(message, code);
    }
}
