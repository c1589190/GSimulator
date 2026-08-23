package com.gsim.agent.tools.cache;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * gsim_cache_list — 列出所有文本缓存文件。
 *
 * <p>扫描 docsDir/.cache/ 目录，返回所有 .txt 文件的 ID 和大小。
 */
public class CacheListTool implements AgentTool {

    public static final String NAME = "cache_list";

    private final Path docsDir;

    public CacheListTool(Path docsDir) {
        this.docsDir = docsDir;
    }

    private Path cacheDir() {
        return docsDir.resolve(".cache");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                列出所有文本缓存文件。
                扫描 .cache/ 目录，返回每个缓存文件的 id（文件名）和大小（字节）。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of());
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Path cacheDir = cacheDir();
        List<Map<String, Object>> caches = new ArrayList<>();

        if (Files.isDirectory(cacheDir)) {
            try (Stream<Path> files = Files.list(cacheDir)) {
                for (Path f : files.sorted().toList()) {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".txt")) continue;
                    try {
                        caches.add(Map.of("id", name, "size", Files.size(f)));
                    } catch (IOException ignored) {
                        caches.add(Map.of("id", name, "size", -1L));
                    }
                }
            } catch (IOException ignored) {
                // directory listing failed
            }
        }

        if (caches.isEmpty()) {
            return ToolResult.ok(NAME, List.of(new ToolResult.Item("empty", NAME, "没有文本缓存文件。", 1.0)));
        }

        StringBuilder sb = new StringBuilder("## 文本缓存列表\n\n");
        sb.append("| # | 缓存 ID | 大小 (bytes) |\n");
        sb.append("|---|---------|-------------|\n");
        int idx = 1;
        for (Map<String, Object> c : caches) {
            sb.append(String.format("| %d | `%s` | %s |\n", idx++, c.get("id"), c.get("size")));
        }
        sb.append("\n共 ").append(caches.size()).append(" 个缓存文件。");

        return ToolResult.ok(NAME, List.of(new ToolResult.Item("cache_list", NAME, sb.toString(), 1.0)));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
