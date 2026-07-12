package com.gsim.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 管理器 — 从 resources/prompts/ 加载和管理 prompt 模板。
 */
public class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);

    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    /**
     * 从 resources/prompts/ 加载所有 prompt 模板。
     */
    public void loadAll() {
        // Phase 2: 暂时只记录，不强制加载（prompt 文件稍后创建）
        log.info("PromptManager initialized (prompts will be loaded in Phase 4)");
    }

    /**
     * 获取指定 prompt 模板。
     */
    public PromptTemplate get(String name) {
        return templates.get(name);
    }

    /**
     * 手动注册模板（用于测试）。
     */
    public void register(String name, PromptTemplate template) {
        templates.put(name, template);
    }

    /**
     * 从 classpath 加载文件内容。
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
