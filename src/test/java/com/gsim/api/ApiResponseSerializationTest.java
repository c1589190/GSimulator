package com.gsim.api;

import com.gsim.util.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiResponse 序列化测试 — 验证 fail(String, Map) 中 data 被正确保留。
 */
@DisplayName("ApiResponse Serialization")
class ApiResponseSerializationTest {

    @Test
    @DisplayName("fail(String, Map) 应在 JSON 中保留 data")
    void failWithDataShouldPreserveDataInJson() {
        Map<String, Object> errorDetails = Map.of("key", "value", "count", 42);
        ApiResponse response = ApiResponse.fail("Something went wrong", errorDetails);

        String json = response.toJson();
        Map<?, ?> result = JsonUtils.fromJson(json, Map.class);

        assertEquals(false, result.get("success"));
        assertNotNull(result.get("data"));
        assertEquals("value", ((Map<?, ?>) result.get("data")).get("key"));
        assertEquals(42, ((Map<?, ?>) result.get("data")).get("count"));
        assertNotNull(result.get("error"));
        assertEquals("Something went wrong", ((Map<?, ?>) result.get("error")).get("message"));
    }

    @Test
    @DisplayName("fail(String) 无 data 时 JSON data 应为 null")
    void failWithoutDataShouldHaveNullData() {
        ApiResponse response = ApiResponse.fail("Error");

        String json = response.toJson();
        Map<?, ?> result = JsonUtils.fromJson(json, Map.class);

        assertEquals(false, result.get("success"));
        assertNull(result.get("data"));
        assertNotNull(result.get("error"));
    }

    @Test
    @DisplayName("ok 响应 data 不应为 null")
    void okResponseShouldHaveData() {
        ApiResponse response = ApiResponse.ok("Success", Map.of("result", "done"));

        String json = response.toJson();
        Map<?, ?> result = JsonUtils.fromJson(json, Map.class);

        assertEquals(true, result.get("success"));
        assertNotNull(result.get("data"));
        assertNull(result.get("error"));
    }

    @Test
    @DisplayName("fail(ApiError, data 为空) data 应返回 null")
    void failWithErrorAndNullDataShouldHaveNullData() {
        ApiResponse response = ApiResponse.fail(ApiError.of("DB error", "DB_ERROR"));

        String json = response.toJson();
        Map<?, ?> result = JsonUtils.fromJson(json, Map.class);

        assertEquals(false, result.get("success"));
        assertNull(result.get("data"));
        assertEquals("DB error", ((Map<?, ?>) result.get("error")).get("message"));
        assertEquals("DB_ERROR", ((Map<?, ?>) result.get("error")).get("code"));
    }
}
