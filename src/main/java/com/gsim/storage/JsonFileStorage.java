package com.gsim.storage;

import com.gsim.util.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * JSON 文件存储 — 通用的 JSON 文件读写实现。
 */
public class JsonFileStorage {

    /**
     * 将对象保存为 JSON 文件。
     */
    public static void save(Path file, Object obj) throws IOException {
        Files.createDirectories(file.getParent());
        String json = JsonUtils.toJson(obj);
        Files.writeString(file, json);
    }

    /**
     * 从 JSON 文件读取对象。
     */
    public static <T> Optional<T> load(Path file, Class<T> clazz) throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String json = Files.readString(file);
        return Optional.of(JsonUtils.fromJson(json, clazz));
    }

    /**
     * 从 JSON 文件读取数组。
     */
    public static <T> List<T> loadArray(Path file, Class<T[]> arrayClass, Class<T> elementClass) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        String json = Files.readString(file);
        T[] arr = JsonUtils.fromJson(json, arrayClass);
        return arr != null ? List.of(arr) : List.of();
    }
}
