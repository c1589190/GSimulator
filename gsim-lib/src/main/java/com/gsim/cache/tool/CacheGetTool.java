package com.gsim.cache.tool;

import com.gsim.llm.ToolDef;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * gsim_cache_get — 读取一个文本缓存文件，支持行级分页。
 *
 * <p>从 docsDir/.cache/ 目录中读取指定缓存文件，按 offset/limit 返回部分行。
 */
public class CacheGetTool implements AgentTool {

    public static final String NAME = "gsim_cache_get";

    private final Path docsDir;

    public CacheGetTool(Path docsDir) {
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
                读取一个文本缓存文件，支持行级分页。
                参数:
                - cacheId (必填): 缓存文件名（如 'crop_20260701_120000_a1b2c3d4.txt'）
                - offset (可选): 起始行号，默认 0
                - limit (可选): 最大返回行数，默认 200
                返回 totalLines、offset、limit、content（缓存文本内容）和 ref（@cache: 引用）。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "cacheId",
                                Map.of(
                                        "type", "string",
                                        "description", "缓存文件名（如 'crop_20260701_120000_a1b2c3d4.txt'）"),
                        "offset",
                                Map.of(
                                        "type", "integer",
                                        "description", "起始行号（默认 0）"),
                        "limit",
                                Map.of(
                                        "type", "integer",
                                        "description", "最大返回行数（默认 200）")),
                List.of("cacheId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String cacheId = call.param("cacheId", "").trim();
        if (cacheId.isEmpty()) {
            return ToolResult.fail(NAME, "cacheId 不能为空");
        }

        int offset = 0;
        int limit = 200;
        String offsetStr = call.param("offset", "").trim();
        String limitStr = call.param("limit", "").trim();
        if (!offsetStr.isEmpty()) {
            try {
                offset = Integer.parseInt(offsetStr);
                if (offset < 0) offset = 0;
            } catch (NumberFormatException e) {
                return ToolResult.fail(NAME, "offset 必须是正整数: " + offsetStr);
            }
        }
        if (!limitStr.isEmpty()) {
            try {
                limit = Integer.parseInt(limitStr);
                if (limit < 0) limit = 200;
            } catch (NumberFormatException e) {
                return ToolResult.fail(NAME, "limit 必须是正整数: " + limitStr);
            }
        }

        Path cacheFile = cacheDir().resolve(cacheId);
        if (!Files.exists(cacheFile)) {
            return ToolResult.fail(NAME, "文本缓存不存在: " + cacheId);
        }

        String content;
        try {
            content = Files.readString(cacheFile);
        } catch (IOException e) {
            return ToolResult.fail(NAME, "读取缓存失败: " + e.getMessage());
        }

        String[] lines = content.split("\n", -1);
        int totalLines = lines.length;
        int from = Math.min(offset, totalLines);
        int to = Math.min(from + limit, totalLines);
        String excerpt = String.join("\n", Arrays.copyOfRange(lines, from, to));

        StringBuilder sb = new StringBuilder("## 文本缓存: `").append(cacheId).append("`\n\n");
        sb.append("- **总行数**: ").append(totalLines).append("\n");
        sb.append("- **当前范围**: 行 ").append(from).append(" - ").append(to - 1).append("\n");
        sb.append("- **@cache 引用**: `@cache:").append(cacheId).append("`\n\n");
        sb.append("```\n").append(excerpt).append("\n```\n");

        return ToolResult.ok(NAME, List.of(new ToolResult.Item("cache:" + cacheId, NAME, sb.toString(), 1.0)));
    }
}
