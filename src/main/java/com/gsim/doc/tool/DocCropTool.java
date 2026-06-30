package com.gsim.doc.tool;

import com.gsim.doc.DocStore;
import com.gsim.doc.Document;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * do上下文裁剪工具 — 从源文档中按行范围提取内容，支持关键词遮蔽和选择性替换。
 *
 * <p>GM 用此工具为不同角色生成差异化视野上下文。
 *
 * <h3>执行顺序</h3>
 * <ol>
 *   <li>按 lines 保留指定行范围</li>
 *   <li>rename — 选择性替换专名（保持叙事连贯）</li>
 *   <li>mask_words — 关键词遮蔽为 ***</li>
 *   <li>mask_lines — 整行遮蔽为 ***</li>
 * </ol>
 */
public final class DocCropTool implements AgentTool {

    private final DocStore store;

    public DocCropTool(DocStore store) {
        this.store = store;
    }

    @Override
    public String name() { return "doc_crop"; }

    @Override
    public String description() {
        return """
                从源文档裁剪出部分内容，可选关键词遮蔽和信息替换。
                用于为不同角色生成差异化视野上下文（信息隐藏）。

                参数:
                - docId: 源文档 ID（必填）
                - lines: 保留行范围，如 "1-6, 11-14, 20"（必填）
                - mask_words: 遮蔽为 *** 的关键词，逗号分隔（可选）
                - mask_lines: 整行遮蔽的范围，如 "8-9"（可选）
                - rename_from: 要替换的原文，逗号分隔（可选，与 rename_to 配合）
                - rename_to: 替换为的文本，逗号分隔（可选，与 rename_from 一一对应）

                示例:
                doc_crop(docId="turn_5_state", lines="1-6, 11-14",
                         mask_words="王允,七星刀", mask_lines="8-9",
                         rename_from="曹操的密使", rename_to="一位神秘来客")
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "docId", Map.of("type", "string", "description", "源文档 ID"),
                        "lines", Map.of("type", "string",
                                "description", "保留行范围，逗号分隔。格式: \"1-6, 11-14, 20\""),
                        "mask_words", Map.of("type", "string",
                                "description", "要遮蔽为 *** 的关键词，逗号分隔（可选）"),
                        "mask_lines", Map.of("type", "string",
                                "description", "整行遮蔽为 *** 的行范围（可选）"),
                        "rename_from", Map.of("type", "string",
                                "description", "要替换的原文，逗号分隔（可选）"),
                        "rename_to", Map.of("type", "string",
                                "description", "替换为的文本，逗号分隔（可选，与 rename_from 一一对应）")
                ),
                "required", List.of("docId", "lines")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String docId = call.param("docId", "").trim();
        String linesSpec = call.param("lines", "").trim();
        String maskWordsStr = call.param("mask_words", "").trim();
        String maskLinesStr = call.param("mask_lines", "").trim();
        String renameFromStr = call.param("rename_from", "").trim();
        String renameToStr = call.param("rename_to", "").trim();

        if (docId.isEmpty()) return ToolResult.fail(name(), "docId 不能为空");
        if (linesSpec.isEmpty()) return ToolResult.fail(name(), "lines 不能为空");

        Document doc = store.get(docId);
        if (doc == null) return ToolResult.fail(name(), "文档不存在: " + docId);

        String[] allLines = doc.content().split("\n", -1);

        // Step 1: 解析保留行范围
        Set<Integer> keepLines = new LinkedHashSet<>();
        for (String part : linesSpec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                if (part.contains("-")) {
                    String[] range = part.split("-");
                    int start = Integer.parseInt(range[0].trim());
                    int end = Integer.parseInt(range[1].trim());
                    for (int i = start; i <= end && i < allLines.length; i++) {
                        keepLines.add(i);
                    }
                } else {
                    int line = Integer.parseInt(part);
                    if (line < allLines.length) keepLines.add(line);
                }
            } catch (NumberFormatException e) {
                return ToolResult.fail(name(), "无法解析行范围: " + part);
            }
        }

        // 保留行按顺序输出
        List<Integer> sorted = new ArrayList<>(keepLines);
        sorted.sort(Integer::compareTo);

        StringBuilder sb = new StringBuilder();
        for (int lineNum : sorted) {
            sb.append(allLines[lineNum]).append("\n");
        }

        String result = sb.toString();

        // Step 2: rename 替换（先执行，让 rename 后的词不会被 mask_words 误伤）
        if (!renameFromStr.isEmpty()) {
            String[] froms = renameFromStr.split(",");
            String[] tos = renameToStr.isEmpty() ? new String[0] : renameToStr.split(",");
            for (int i = 0; i < froms.length; i++) {
                String from = froms[i].trim();
                String to = i < tos.length ? tos[i].trim() : "***";
                if (!from.isEmpty()) {
                    result = result.replace(from, to);
                }
            }
        }

        // Step 3: mask_words 遮蔽
        if (!maskWordsStr.isEmpty()) {
            for (String word : maskWordsStr.split(",")) {
                word = word.trim();
                if (!word.isEmpty()) {
                    result = result.replace(word, "***");
                }
            }
        }

        // Step 4: mask_lines 整行遮蔽
        if (!maskLinesStr.isEmpty()) {
            String[] maskedLines = result.split("\n", -1);
            for (String part : maskLinesStr.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                try {
                    if (part.contains("-")) {
                        String[] range = part.split("-");
                        int start = Integer.parseInt(range[0].trim());
                        int end = Integer.parseInt(range[1].trim());
                        for (int i = start; i <= end && i < maskedLines.length; i++) {
                            maskedLines[i] = "***";
                        }
                    } else {
                        int line = Integer.parseInt(part);
                        if (line < maskedLines.length) maskedLines[line] = "***";
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            result = String.join("\n", maskedLines);
        }

        // 重新编号：去掉行号前缀（如果有），输出干净的行号
        String[] resultLines = result.split("\n", -1);
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < resultLines.length; i++) {
            output.append(String.format("%6d| ", i)).append(resultLines[i]).append("\n");
        }

        return ToolResult.ok(name(), List.of(new ToolResult.Item(
                doc.title() + " (裁剪视图)", docId,
                output.toString(), 1.0)));
    }
}
