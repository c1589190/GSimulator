package com.gsim.text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 通用文本编辑器 — 对任意文本执行行级和关键词级操作，返回编辑后的文本。
 *
 * <p>操作按固定顺序执行：
 * <ol>
 *   <li>select_lines — 保留指定行范围</li>
 *   <li>delete_lines — 删除行范围</li>
 *   <li>insert_lines — 在指定行前插入</li>
 *   <li>replace_lines — 替换行范围</li>
 *   <li>replace_kw — 关键词替换（两遍防重叠）</li>
 *   <li>mask_kw — 关键词遮蔽</li>
 *   <li>mask_lines — 整行遮蔽</li>
 * </ol>
 *
 * <p>纯函数，不依赖任何外部状态，可被 Tool 和 HTTP API 复用。
 */
public final class TextEditor {

    private TextEditor() {}

    // ── Result ──

    public record EditResult(String text, int originalLines, int resultLines, List<String> appliedOps) {
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(originalLines).append(" 行 → ").append(resultLines).append(" 行");
            if (!appliedOps.isEmpty()) {
                sb.append(" (").append(String.join(", ", appliedOps)).append(")");
            }
            return sb.toString();
        }
    }

    // ── Operation descriptors ──

    public sealed interface Op
            permits SelectLines, DeleteLines, InsertLines, ReplaceLines, ReplaceKeyword, MaskKeyword, MaskLines {}

    public record SelectLines(String spec) implements Op {}

    public record DeleteLines(String spec) implements Op {}

    public record InsertLines(int at, String text) implements Op {}

    public record ReplaceLines(String spec, String text) implements Op {}

    public record ReplaceKeyword(String from, String to) implements Op {}

    public record MaskKeyword(String words) implements Op {}

    public record MaskLines(String spec) implements Op {}

    // ── Main pipeline ──

    /**
     * 对文本执行一组操作，返回编辑结果。
     *
     * <p>操作按固定顺序执行：select → delete → insert → replace_lines → replace_kw → mask_kw → mask_lines。
     *
     * @param source 原始文本
     * @param ops    要执行的操作列表
     * @return 编辑结果，包含最终文本和操作摘要
     */
    public static EditResult edit(String source, List<Op> ops) {
        String[] lines = source.split("\n", -1);
        int origLines = lines.length;
        List<String> applied = new ArrayList<>();

        // 1. select_lines — 保留指定行（基于原始行号）
        if (hasOp(ops, SelectLines.class)) {
            SelectLines op = findOp(ops, SelectLines.class);
            Set<Integer> keep = parseLineSpec(op.spec, lines.length);
            List<String> kept = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (keep.contains(i)) kept.add(lines[i]);
            }
            lines = kept.toArray(new String[0]);
            applied.add("select_lines(" + op.spec + ")");
        }

        // 2. delete_lines — 删除行（基于当前行号）
        if (hasOp(ops, DeleteLines.class)) {
            DeleteLines op = findOp(ops, DeleteLines.class);
            Set<Integer> toDelete = parseLineSpec(op.spec, lines.length);
            List<String> kept = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (!toDelete.contains(i)) kept.add(lines[i]);
            }
            lines = kept.toArray(new String[0]);
            applied.add("delete_lines(" + op.spec + ")");
        }

        // 3. insert_lines
        for (Op op : ops) {
            if (op instanceof InsertLines il) {
                int at = Math.min(il.at, lines.length);
                String[] inserted = il.text.split("\n", -1);
                List<String> newLines = new ArrayList<>();
                for (int i = 0; i < at; i++) newLines.add(lines[i]);
                for (String s : inserted) newLines.add(s);
                for (int i = at; i < lines.length; i++) newLines.add(lines[i]);
                lines = newLines.toArray(new String[0]);
                applied.add("insert_lines(at=" + il.at + ")");
            }
        }

        // 4. replace_lines
        for (Op op : ops) {
            if (op instanceof ReplaceLines rl) {
                Set<Integer> toReplace = parseLineSpec(rl.spec, lines.length);
                String[] replaced = rl.text.split("\n", -1);
                if (toReplace.isEmpty()) continue;
                int min = Integer.MAX_VALUE, max = -1;
                for (int i : toReplace) {
                    min = Math.min(min, i);
                    max = Math.max(max, i);
                }
                List<String> newLines = new ArrayList<>();
                for (int i = 0; i < min; i++) newLines.add(lines[i]);
                for (String s : replaced) newLines.add(s);
                for (int i = max + 1; i < lines.length; i++) newLines.add(lines[i]);
                lines = newLines.toArray(new String[0]);
                applied.add("replace_lines(" + rl.spec + ")");
            }
        }

        // 5-7. Text-level operations
        String text = String.join("\n", lines);

        // 5. replace_kw — two-pass to avoid overlap
        for (Op op : ops) {
            if (op instanceof ReplaceKeyword rk) {
                String[] froms = rk.from.split(",");
                String[] tos = rk.to.isEmpty() ? new String[0] : rk.to.split(",");

                String[] placeholders = new String[froms.length];
                for (int i = 0; i < froms.length; i++) {
                    placeholders[i] = "__TEXTEDIT_REN_" + i + "__";
                }

                // Pass 1: from → placeholder
                for (int i = 0; i < froms.length; i++) {
                    String f = froms[i].trim();
                    if (!f.isEmpty()) text = text.replace(f, placeholders[i]);
                }
                // Pass 2: placeholder → to (excess froms revert)
                for (int i = 0; i < froms.length; i++) {
                    String f = froms[i].trim();
                    if (f.isEmpty()) continue;
                    if (i < tos.length && !tos[i].trim().isEmpty()) {
                        text = text.replace(placeholders[i], tos[i].trim());
                    } else {
                        text = text.replace(placeholders[i], f);
                    }
                }
                // Pass 3: collapse adjacent duplicates from replacement
                for (int i = 0; i < froms.length; i++) {
                    if (i < tos.length && !tos[i].trim().isEmpty()) {
                        String t = tos[i].trim();
                        text = text.replaceAll(Pattern.quote(t) + "(" + Pattern.quote(t) + ")+", t);
                    }
                }
                applied.add("replace_kw(" + rk.from + "→" + rk.to + ")");
            }
        }

        // 6. mask_kw
        for (Op op : ops) {
            if (op instanceof MaskKeyword mk) {
                for (String w : mk.words.split(",")) {
                    w = w.trim();
                    if (!w.isEmpty()) text = text.replace(w, "***");
                }
                applied.add("mask_kw(" + mk.words + ")");
            }
        }

        // 7. mask_lines — whole-line masking (back to line array for this)
        lines = text.split("\n", -1);
        for (Op op : ops) {
            if (op instanceof MaskLines ml) {
                Set<Integer> toMask = parseLineSpec(ml.spec, lines.length);
                for (int i : toMask) {
                    if (i < lines.length) lines[i] = "***";
                }
                applied.add("mask_lines(" + ml.spec + ")");
            }
        }
        text = String.join("\n", lines);

        return new EditResult(text, origLines, text.isEmpty() ? 0 : text.split("\n", -1).length, applied);
    }

    // ── Line spec parsing: "1-6, 11-14, 20" → Set of 0-based line indices ──

    static Set<Integer> parseLineSpec(String spec, int totalLines) {
        Set<Integer> result = new LinkedHashSet<>();
        if (spec == null || spec.isBlank()) return result;
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                if (part.contains("-")) {
                    String[] range = part.split("-");
                    int start = Integer.parseInt(range[0].trim());
                    int end = Integer.parseInt(range[1].trim());
                    for (int i = start; i <= end && i < totalLines; i++) {
                        result.add(i);
                    }
                } else {
                    int line = Integer.parseInt(part);
                    if (line >= 0 && line < totalLines) result.add(line);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    // ── Op helpers ──

    @SuppressWarnings("unchecked")
    private static <T extends Op> boolean hasOp(List<Op> ops, Class<T> type) {
        for (Op op : ops) {
            if (type.isInstance(op)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Op> T findOp(List<Op> ops, Class<T> type) {
        for (Op op : ops) {
            if (type.isInstance(op)) return (T) op;
        }
        return null;
    }

    // ── Parse line count without splitting ──

    static int countLines(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }
}
