package com.gsim.agent.tools.cache;

import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.llm.ToolDef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * gsim_cache_edit — 编辑一个文本缓存文件。
 *
 * <p>支持关键词替换（replace_from -> replace_to）和文本追加（insert_text）。
 * 编辑结果保存为新的缓存文件，不修改原始文件。
 */
public class CacheEditTool implements AgentTool {

    public static final String NAME = "cache_edit";

    private final Path docsDir;

    public CacheEditTool(Path docsDir) {
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
                编辑一个文本缓存文件。支持关键词替换和文本追加。
                参数:
                - cacheId (必填): 源缓存文件名
                - replace_from (可选): 要查找替换的文本
                - replace_to (可选): 替换后的文本（与 replace_from 配合使用）
                - insert_text (可选): 追加到文件末尾的文本
                编辑结果保存为新的缓存文件，原始文件不变。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "cacheId",
                                Map.of(
                                        "type", "string",
                                        "description", "源缓存文件名"),
                        "replace_from",
                                Map.of(
                                        "type", "string",
                                        "description", "要查找替换的文本（可选）"),
                        "replace_to",
                                Map.of(
                                        "type", "string",
                                        "description", "替换后的文本（可选，与 replace_from 配合使用）"),
                        "insert_text",
                                Map.of(
                                        "type", "string",
                                        "description", "追加到文件末尾的文本（可选）")),
                List.of("cacheId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String cacheId = call.param("cacheId", "").trim();
        if (cacheId.isEmpty()) {
            return ToolResult.fail(NAME, "cacheId 不能为空");
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

        boolean modified = false;

        // Keyword replacement
        String replaceFrom = call.param("replace_from", "").trim();
        String replaceTo = call.param("replace_to", "").trim();
        if (!replaceFrom.isEmpty() && !replaceTo.isEmpty()) {
            content = content.replace(replaceFrom, replaceTo);
            modified = true;
        } else if (!replaceFrom.isEmpty() && replaceTo.isEmpty()) {
            // replace_from provided without replace_to — treat as delete
            content = content.replace(replaceFrom, "");
            modified = true;
        }

        // Append text
        String insertText = call.param("insert_text", "").trim();
        if (!insertText.isEmpty()) {
            content = content + "\n" + insertText;
            modified = true;
        }

        if (!modified) {
            return ToolResult.ok(
                    NAME,
                    List.of(new ToolResult.Item(
                            "unchanged:" + cacheId,
                            NAME,
                            "未做任何修改（请提供 replace_from + replace_to 或 insert_text）。",
                            1.0)));
        }

        // Save as new cache file
        String baseName = cacheId;
        if (baseName.endsWith(".txt")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        String newId = "edited_" + System.currentTimeMillis() + "_" + baseName + ".txt";

        Path cacheDir = cacheDir();
        Path newFile = cacheDir.resolve(newId);
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(newFile, content);
        } catch (IOException e) {
            return ToolResult.fail(NAME, "保存编辑后的缓存失败: " + e.getMessage());
        }

        StringBuilder sb = new StringBuilder("## 缓存编辑完成\n\n");
        sb.append("- **源文件**: `").append(cacheId).append("`\n");
        sb.append("- **新文件**: `").append(newId).append("`\n");
        sb.append("- **@cache 引用**: `@cache:").append(newId).append("`\n");
        if (!replaceFrom.isEmpty()) {
            sb.append("- **替换**: \"")
                    .append(replaceFrom)
                    .append("\" → \"")
                    .append(replaceTo)
                    .append("\"\n");
        }
        if (!insertText.isEmpty()) {
            sb.append("- **追加**: ").append(truncate(insertText, 100)).append("\n");
        }

        return ToolResult.ok(NAME, List.of(new ToolResult.Item("edited:" + newId, NAME, sb.toString(), 1.0)));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
