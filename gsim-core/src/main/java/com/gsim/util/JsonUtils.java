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
     * 对象转 JSON 字符串（格式化输出）。
     *
     * @param obj 要序列化的对象
     * @return JSON 字符串
     * @throws RuntimeException JSON 序列化失败时抛出
     */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * 对象转紧凑 JSON 字符串（单行，无缩进）。
     *
     * @param obj 要序列化的对象
     * @return 紧凑格式的 JSON 字符串
     * @throws RuntimeException JSON 序列化失败时抛出
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
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   目标类型泛型
     * @return 反序列化后的对象
     * @throws RuntimeException JSON 解析失败时抛出
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
     *
     * @param json    JSON 字符串
     * @param typeRef 泛型类型引用
     * @param <T>     目标类型泛型
     * @return 反序列化后的对象
     * @throws RuntimeException JSON 解析失败时抛出
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
     * <p>
     * 当前为简单实现，会尝试去除 markdown 代码块标记后重新解析。
     * 未来可扩展完整的 JSON repair 逻辑。
     *
     * @param json  JSON 字符串（可能格式错误）
     * @param clazz 目标类型
     * @param <T>   目标类型泛型
     * @return 反序列化后的对象
     * @throws RuntimeException JSON 解析修复失败时抛出
     */
    public static <T> T fromJsonWithRepair(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException firstAttempt) {
            // 尝试修复：去除 markdown 代码块标记
            String repaired = json.replaceAll("^```(?:json)?\\s*", "")
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
