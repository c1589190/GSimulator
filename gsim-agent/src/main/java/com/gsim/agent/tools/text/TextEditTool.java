package com.gsim.agent.tools.text;

import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.doc.DocCacheManager;
import com.gsim.docslib.doc.DocStore;
import com.gsim.core.ref.RefResolver.ResolvedRef;
import com.gsim.core.ref.ResolverContext;
import com.gsim.core.ref.ResolverRegistry;
import com.gsim.core.text.TextEditor;
import com.gsim.core.text.TextEditor.EditResult;
import com.gsim.core.text.TextEditor.Op;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用文本编辑工具 — 对任何来源的文本执行行级/关键词级修改。
 *
 * <p>输入 source 支持：
 * <ul>
 *   <li>{@code @cache:id} — 缓存文本</li>
 *   <li>{@code @world:nodeId:cpId:key} 或 {@code @world:cpId:key} — World 元素</li>
 *   <li>{@code @doc:id} — Doc/Board 文档</li>
 *   <li>{@code @import:id} — Import 文档</li>
 *   <li>裸文本 — 直接编辑</li>
 * </ul>
 *
 * <p>操作（按此顺序执行）：
 * <ul>
 *   <li>select_lines — 保留指定行，如 "1-6, 11-14, 20"</li>
 *   <li>delete_lines — 删除行范围，如 "7-10, 22"</li>
 *   <li>insert_at + insert_text — 在指定行前插入文本</li>
 *   <li>replace_spec + replace_text — 用新文本替换行范围</li>
 *   <li>replace_from + replace_to — 关键词替换（逗号分隔，一一对应）</li>
 *   <li>mask_kw — 关键词遮蔽为 ***</li>
 *   <li>mask_lines_spec — 整行遮蔽为 ***</li>
 * </ul>
 */
public final class TextEditTool implements AgentTool {

    public static final String NAME = "text_edit";

    private final ResolverRegistry registry;
    private final Path worldsDir;
    private final String activeWorldId;
    private final Path importDir;
    private final DocStore docStore;
    private final DocCacheManager cacheManager;

    /**
     * 创建文本编辑工具。
     *
     * @param registry      统一引用解析注册中心（@world/@doc/@cache/@import/gsimap:）
     * @param worldsDir     世界数据根目录
     * @param activeWorldId 当前活跃的世界 ID
     * @param importDir     导入文档目录
     * @param docStore      文档存储
     * @param cacheManager  文档缓存管理器
     */
    public TextEditTool(
            ResolverRegistry registry,
            Path worldsDir,
            String activeWorldId,
            Path importDir,
            DocStore docStore,
            DocCacheManager cacheManager) {
        this.registry = registry;
        this.worldsDir = worldsDir;
        this.activeWorldId = activeWorldId;
        this.importDir = importDir;
        this.docStore = docStore;
        this.cacheManager = cacheManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                对任意来源的文本进行行级和关键词级编辑。

                参数:
                - source (必填): 文本来源 — @cache:id、@world:ref、@doc:id、@import:id 或裸文本。
                - select_lines: 保留的行范围，如 "1-6, 11-14, 20"（可选）
                - delete_lines: 删除的行范围，如 "7-10, 22"（可选）
                - insert_at: 插入位置行号（0-based，可选，与 insert_text 配合）
                - insert_text: 要插入的文本（可选，与 insert_at 配合）
                - replace_spec: 要替换的行范围，如 "3-4"（可选，与 replace_text 配合）
                - replace_text: 替换为的文本（可选，与 replace_spec 配合）
                - replace_from: 要替换的关键词，逗号分隔（可选，与 replace_to 配合）
                - replace_to: 替换成的文本，逗号分隔（可选，与 replace_from 一一对应）
                - mask_kw: 要遮蔽为 *** 的关键词，逗号分隔（可选）
                - mask_lines_spec: 整行遮蔽的行范围，如 "8-9"（可选）

                操作按 select → delete → insert → replace_lines → replace_kw → mask_kw → mask_lines 顺序执行。

                示例:
                text_edit(source="@doc:turn_5_state", select_lines="1-6,11-14", mask_kw="王允",
                          replace_from="曹操的密使", replace_to="神秘来客")
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put(
                "source", Map.of("type", "string", "description", "文本来源：@cache:id、@world:ref、@doc:id、@import:id 或裸文本"));
        props.put("select_lines", Map.of("type", "string", "description", "保留的行范围，如 \"1-6, 11-14, 20\"（可选）"));
        props.put("delete_lines", Map.of("type", "string", "description", "删除的行范围，如 \"7-10, 22\"（可选）"));
        props.put("insert_at", Map.of("type", "integer", "description", "插入位置行号 (0-based)（可选，与 insert_text 配合）"));
        props.put("insert_text", Map.of("type", "string", "description", "要插入的文本（可选，与 insert_at 配合）"));
        props.put("replace_spec", Map.of("type", "string", "description", "要替换的行范围，如 \"3-4\"（可选，与 replace_text 配合）"));
        props.put("replace_text", Map.of("type", "string", "description", "替换为的文本（可选，与 replace_spec 配合）"));
        props.put("replace_from", Map.of("type", "string", "description", "要替换的关键词，逗号分隔（可选）"));
        props.put("replace_to", Map.of("type", "string", "description", "替换为的文本，逗号分隔（可选）"));
        props.put("mask_kw", Map.of("type", "string", "description", "要遮蔽为 *** 的关键词，逗号分隔（可选）"));
        props.put("mask_lines_spec", Map.of("type", "string", "description", "整行遮蔽的行范围，如 \"8-9\"（可选）"));
        return Map.of("type", "object", "properties", props, "required", List.of("source"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String source = call.param("source", "").trim();
        if (source.isEmpty()) return ToolResult.fail(NAME, "source 不能为空");

        // 从 ToolCall 参数获取 worldId，支持活跃 World 动态切换后的解析
        String worldId = call.param("worldId", activeWorldId);

        // Resolve source text
        String sourceText;
        String sourceLabel;
        try {
            SourceResolved resolved = resolveSource(source, worldId);
            sourceText = resolved.text;
            sourceLabel = resolved.label;
        } catch (Exception e) {
            return ToolResult.fail(NAME, "无法解析 source: " + e.getMessage());
        }

        // Build op list from parameters
        List<Op> ops = new ArrayList<>();

        String selectLines = str(call.param("select_lines"));
        if (!selectLines.isEmpty()) ops.add(new TextEditor.SelectLines(selectLines));

        String deleteLines = str(call.param("delete_lines"));
        if (!deleteLines.isEmpty()) ops.add(new TextEditor.DeleteLines(deleteLines));

        int insertAt = parseInt(call.param("insert_at"), -1);
        String insertText = str(call.param("insert_text"));
        if (insertAt >= 0 && !insertText.isEmpty()) {
            ops.add(new TextEditor.InsertLines(insertAt, insertText));
        }

        String replaceSpec = str(call.param("replace_spec"));
        String replaceText = str(call.param("replace_text"));
        if (!replaceSpec.isEmpty() && !replaceText.isEmpty()) {
            ops.add(new TextEditor.ReplaceLines(replaceSpec, replaceText));
        }

        String replaceFrom = str(call.param("replace_from"));
        String replaceTo = str(call.param("replace_to"));
        if (!replaceFrom.isEmpty()) {
            ops.add(new TextEditor.ReplaceKeyword(replaceFrom, replaceTo));
        }

        String maskKw = str(call.param("mask_kw"));
        if (!maskKw.isEmpty()) ops.add(new TextEditor.MaskKeyword(maskKw));

        String maskLinesSpec = str(call.param("mask_lines_spec"));
        if (!maskLinesSpec.isEmpty()) ops.add(new TextEditor.MaskLines(maskLinesSpec));

        if (ops.isEmpty()) {
            return ToolResult.fail(NAME, "至少需要一个编辑操作");
        }

        // Execute
        EditResult result = TextEditor.edit(sourceText, ops);

        // Build output with line numbers
        StringBuilder sb = new StringBuilder();
        String[] lines = result.text().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%6d| ", i)).append(lines[i]).append("\n");
        }
        sb.append("\n---\n");
        sb.append("源: ").append(sourceLabel).append("\n");
        sb.append("编辑: ").append(result.summary()).append("\n");

        return ToolResult.ok(
                NAME,
                List.of(new ToolResult.Item(
                        "text_edit: " + sourceLabel + " → " + result.summary(), source, sb.toString(), 1.0)));
    }

    // ── Source resolution ──

    private record SourceResolved(String text, String label) {}

    private SourceResolved resolveSource(String source, String worldId) {
        // @ 前缀或 gsimap: 前缀 → 统一走 ResolverRegistry（@world 两段式解析到活跃节点；gsimap: 解析地图实体）
        if (source.startsWith("@") || source.startsWith("gsimap:")) {
            Path cacheDir = cacheManager.cacheDir();
            ResolverContext ctx = ResolverContext.of(worldsDir, worldId, importDir, docStore, cacheDir);
            ResolvedRef resolved = registry.resolve(source, ctx);
            return new SourceResolved(resolved.content(), resolved.title() + " [" + resolved.source() + "]");
        }
        // Raw text
        return new SourceResolved(source, "raw text (" + source.length() + " chars)");
    }

    // ── Helpers ──

    private static String str(String s) {
        return s == null ? "" : s.trim();
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
