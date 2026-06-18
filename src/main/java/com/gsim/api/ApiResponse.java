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
 *   "message": "...",
 *   "data": { ... }
 * }
 * </pre>
 */
public class ApiResponse {

    private final boolean success;
    private final String message;
    private final Map<String, Object> data;

    private ApiResponse(boolean success, String message, Map<String, Object> data) {
        this.success = success;
        this.message = message;
        this.data = data != null ? data : Map.of();
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getData() {
        return data;
    }

    /**
     * 转为 JSON 字符串。
     */
    public String toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        map.put("message", message);
        if (!data.isEmpty()) {
            map.put("data", data);
        }
        return JsonUtils.toJson(map);
    }

    // ---- 工厂方法 ----

    public static ApiResponse ok(String message) {
        return new ApiResponse(true, message, Map.of());
    }

    public static ApiResponse ok(String message, Map<String, Object> data) {
        return new ApiResponse(true, message, data);
    }

    public static ApiResponse fail(String errorMessage) {
        return new ApiResponse(false, errorMessage, Map.of());
    }

    public static ApiResponse fail(String errorMessage, Map<String, Object> data) {
        return new ApiResponse(false, errorMessage, data);
    }

    public static ApiResponse notImplemented() {
        return new ApiResponse(false, "This endpoint is not yet implemented.", Map.of());
    }

    @Override
    public String toString() {
        return toJson();
    }
}
