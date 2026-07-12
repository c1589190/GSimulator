package com.gsim.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Jackson JSON 工具类。
 * 全局共享一个 ObjectMapper 实例。
 */
public final class JsonUtils {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);

    private JsonUtils() {
        // utility class
    }

    /**
     * 对象转 JSON 字符串。
     */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * 对象转紧凑 JSON 字符串（单行）。
     */
    public static String toJsonCompact(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * JSON 字符串转对象。
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON deserialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * JSON 字符串转对象（带泛型）。
     */
    public static <T> T fromJson(String json, com.fasterxml.jackson.core.type.TypeReference<T> typeRef) {
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON deserialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * 尝试修复格式错误的 JSON 并重新解析。
     * 当前为简单实现，未来可扩展完整的 JSON repair 逻辑。
     */
    public static <T> T fromJsonWithRepair(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException firstAttempt) {
            // 尝试修复：去除 markdown 代码块标记
            String repaired = json
                    .replaceAll("^```(?:json)?\\s*", "")
                    .replaceAll("\\s*```$", "")
                    .trim();
            try {
                return MAPPER.readValue(repaired, clazz);
            } catch (JsonProcessingException secondAttempt) {
                throw new RuntimeException(
                        "JSON parse failed after repair attempt. Original: " + firstAttempt.getMessage()
                                + "; After repair: " + secondAttempt.getMessage(),
                        secondAttempt);
            }
        }
    }
}
