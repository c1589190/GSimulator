package com.gsim.resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 资源管理器 — 从 classpath 读取模板和提示词，支持 {{key}} 变量替换。
 */
public class ResourceManager {

    private static final ClassLoader CL = Thread.currentThread().getContextClassLoader();

    /**
     * 读取 classpath 资源全文。
     *
     * @param classpathPath classpath 路径（如 {@code "gsim/prompts/system.md"}）
     * @return 文件内容字符串（UTF-8 编码）
     * @throws IOException 如果资源未找到或读取失败
     */
    public static String readText(String classpathPath) throws IOException {
        InputStream in = CL.getResourceAsStream(classpathPath);
        if (in == null) throw new IOException("Resource not found on classpath: " + classpathPath);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return r.lines().collect(Collectors.joining("\n"));
        }
    }

    /**
     * 读取模板并替换 {@code {{key}}} 为 values 映射中的值。
     *
     * @param classpathPath classpath 路径
     * @param values       变量键值对映射
     * @return 渲染后的文本
     * @throws IOException 如果资源未找到或读取失败
     */
    public static String renderTemplate(String classpathPath, Map<String, String> values) throws IOException {
        String template = readText(classpathPath);
        for (var entry : values.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return template;
    }

    /**
     * 便捷方法：读取模板，用键值对参数方式替换（key1, value1, key2, value2, ...）。
     *
     * @param classpathPath classpath 路径
     * @param keyValues     交替出现的键值对序列
     * @return 渲染后的文本
     * @throws IOException 如果资源未找到或读取失败
     */
    public static String renderTemplate(String classpathPath, String... keyValues) throws IOException {
        String template = readText(classpathPath);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            template = template.replace("{{" + keyValues[i] + "}}", keyValues[i + 1]);
        }
        return template;
    }
}
