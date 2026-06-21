package com.gsim.api;

import com.gsim.util.JsonUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一 API 响应包装。
 *
 * <p>所有 HTTP API 返回此格式：
 * <pre>
 * {
 *   "success": true|false,
 *   "data": { ... },
 *   "error": null | { "message": "...", "code": "..." }
 * }
 * </pre>
 */
public class ApiResponse {

    private final boolean success;
    private final Map<String, Object> data;
    private final ApiError error;

    private ApiResponse(boolean success, Map<String, Object> data, ApiError error) {
        this.success = success;
        this.data = data != null ? data : Map.of();
        this.error = error;
    }

    public boolean isSuccess() {
        return success;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public ApiError getError() {
        return error;
    }

    /**
     * 转为 JSON 字符串。
     */
    public String toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        if (success) {
            map.put("data", data.isEmpty() ? null : data);
            map.put("error", null);
        } else {
            map.put("data", data.isEmpty() ? null : data);
            map.put("error", error != null ? error : ApiError.of("Unknown error"));
        }
        return JsonUtils.toJson(map);
    }

    // ---- 工厂方法 ----

    public static ApiResponse ok(Map<String, Object> data) {
        return new ApiResponse(true, data, null);
    }

    public static ApiResponse ok(String message, Map<String, Object> data) {
        Map<String, Object> merged = new LinkedHashMap<>(data != null ? data : Map.of());
        merged.put("message", message);
        return new ApiResponse(true, merged, null);
    }

    public static ApiResponse ok(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", message);
        return new ApiResponse(true, data, null);
    }

    public static ApiResponse fail(ApiError error) {
        return new ApiResponse(false, null, error);
    }

    public static ApiResponse fail(String errorMessage) {
        return new ApiResponse(false, null, ApiError.of(errorMessage));
    }

    public static ApiResponse fail(String errorMessage, Map<String, Object> data) {
        // 保留 data 用于向后兼容（如 CommandApiHandler 传递错误详情）
        return new ApiResponse(false, data, ApiError.of(errorMessage));
    }

    public static ApiResponse fail(String errorMessage, String errorCode) {
        return new ApiResponse(false, null, ApiError.of(errorMessage, errorCode));
    }

    public static ApiResponse notImplemented() {
        return new ApiResponse(false, null, ApiError.of("This endpoint is not yet implemented.", "NOT_IMPLEMENTED"));
    }

    @Override
    public String toString() {
        return toJson();
    }
}
