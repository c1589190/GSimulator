package com.gsim.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * JSON 请求体解析器。
 */
public class JsonBodyParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从 InputStream 解析 JSON 为指定类型。
     *
     * @param in    输入流
     * @param clazz 目标类型
     * @param <T>   目标类型
     * @return 解析后的对象
     * @throws IOException 解析失败时抛出
     */
    public static <T> T parse(InputStream in, Class<T> clazz) throws IOException {
        return MAPPER.readValue(in, clazz);
    }

    /**
     * 从字符串解析 JSON 为指定类型。
     */
    public static <T> T parse(String json, Class<T> clazz) throws IOException {
        return MAPPER.readValue(json, clazz);
    }

    /**
     * 将对象序列化为 JSON 字节数组。
     */
    public static byte[] toBytes(Object obj) throws IOException {
        return MAPPER.writeValueAsBytes(obj);
    }
}
