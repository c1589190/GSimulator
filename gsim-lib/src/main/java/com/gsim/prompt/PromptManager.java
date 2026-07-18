package com.gsim.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prompt 管理器 — 从 resources/prompts/ 加载和管理 prompt 模板。
 */
public class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);

    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    /**
     * 从 resources/prompts/ 加载所有 prompt 模板。
     * <p>当前为轻量实现，仅记录日志，实际加载推迟到后续阶段。</p>
     */
    public void loadAll() {
        // Phase 2: 暂时只记录，不强制加载（prompt 文件稍后创建）
        log.info("PromptManager initialized (prompts will be loaded in Phase 4)");
    }

    /**
     * 获取指定名称的 prompt 模板。
     *
     * @param name 模板名称
     * @return 对应的 PromptTemplate，如果未注册则返回 {@code null}
     */
    public PromptTemplate get(String name) {
        return templates.get(name);
    }

    /**
     * 手动注册 prompt 模板（用于测试或动态注册）。
     *
     * @param name     模板名称
     * @param template 模板实例
     */
    public void register(String name, PromptTemplate template) {
        templates.put(name, template);
    }

    /**
     * 从 classpath 加载资源文件内容。
     *
     * @param resourcePath classpath 路径（如 {@code "gsim/prompts/system.md"}）
     * @return 文件内容字符串（UTF-8 编码）
     * @throws IOException 如果资源未找到或读取失败
     */
    public String loadResource(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
