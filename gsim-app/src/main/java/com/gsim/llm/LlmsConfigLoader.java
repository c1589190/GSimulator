package com.gsim.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * llms.json 加载器 — 负责加载、创建默认模板、提供格式化输出。
 *
 * <h3>加载逻辑</h3>
 * <ol>
 *   <li>若 llms.json 存在 → 加载所有 provider</li>
 *   <li>若不存在 → 从旧环境变量创建默认模板，写入 llms.json，打印提示</li>
 * </ol>
 */
public class LlmsConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(LlmsConfigLoader.class);

    private final Path llmsPath;

    public LlmsConfigLoader(Path llmsPath) {
        this.llmsPath = llmsPath;
    }

    /** 加载 llms.json，不存在则创建默认模板。 */
    public LoadResult load() {
        if (Files.exists(llmsPath)) {
            try {
                LlmsConfigFile file = LlmsConfigFile.load(llmsPath);
                log.info("Loaded llms.json: {} provider(s)", file.providers().size());
                return LoadResult.loaded(file);
            } catch (IOException e) {
                log.error("Failed to load llms.json: {}", e.getMessage());
                // Fallback: create default
                return createDefault();
            }
        } else {
            return createDefault();
        }
    }

    /** 重新加载（不创建默认模板）。 */
    public LlmsConfigFile reload() throws IOException {
        if (!Files.exists(llmsPath)) {
            throw new IOException("llms.json not found: " + llmsPath);
        }
        return LlmsConfigFile.load(llmsPath);
    }

    private LoadResult createDefault() {
        LlmsConfigFile template = LlmsConfigFile.createDefaultTemplate();
        try {
            template.save(llmsPath);
            log.info("Created default llms.json at {}", llmsPath);
        } catch (IOException e) {
            log.error("Failed to write default llms.json: {}", e.getMessage());
        }
        return LoadResult.created(template);
    }

    /** 格式化 provider 列表供 CLI 显示。 */
    public static String formatProviderList(LlmsConfigFile file) {
        StringBuilder sb = new StringBuilder();
        sb.append("  LLM Providers:\n");
        for (LlmConfig c : file.providers()) {
            sb.append(String.format("    [%s] %s — %s @ %s%s%n",
                    c.id(),
                    c.name(),
                    c.model(),
                    c.baseUrl(),
                    c.isDefault() ? " (默认)" : ""));
        }
        return sb.toString();
    }

    public Path getLlmsPath() { return llmsPath; }

    /** 加载结果。 */
    public record LoadResult(
            LlmsConfigFile file,
            boolean wasNewlyCreated
    ) {
        public static LoadResult loaded(LlmsConfigFile f) { return new LoadResult(f, false); }
        public static LoadResult created(LlmsConfigFile f) { return new LoadResult(f, true); }
    }
}
