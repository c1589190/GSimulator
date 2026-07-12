package com.gsim.agent;

import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLI 流式预览渲染器 — ANSI 灰框。
 *
 * <p>自管理每个 stream 的 content/reasoning 缓冲区，不依赖外部 registry。
 *
 * <p>典型效果：
 * <pre>
 * ┌─ LLM 正在输出 ─────────────────┐
 * │ 思考：                         │
 * │ ……reasoning……                  │
 * │                                │
 * │ 输出：                         │
 * │ ……content……                    │
 * └────────────────────────────────┘
 * </pre>
 */
public class CliStreamPreviewRenderer {

    private static final String ANSI_GREY = "\033[90m";
    private static final String ANSI_RESET = "\033[0m";

    private static final String TL = "┌";
    private static final String TR = "┐";
    private static final String BL = "└";
    private static final String BR = "┘";
    private static final String H = "─";
    private static final String V = "│";

    private static final int BOX_WIDTH = 72;

    private final PrintStream out;
    private final boolean enabled;
    private final int maxChars;
    private final boolean showReasoning;
    private final boolean ansiSupported;

    // JLine3 Terminal — 注入后用于可靠的光标控制
    private org.jline.terminal.Terminal jlineTerminal;

    // 缓存裸 PrintStream 的 PrintWriter，避免 resolveWriter() 每次 new 导致多缓冲区交错
    private java.io.PrintWriter cachedWriter;

    // 上一次渲染的行数，用于清除旧框
    private int lastPrintedLines = 0;
    private String lastStreamId = null;

    // 自管理：每个 stream 的内容缓冲区
    private final Map<String, StringBuilder> contentBufs = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> reasoningBufs = new ConcurrentHashMap<>();
    private final Map<String, Integer> toolCallDeltaCounts = new ConcurrentHashMap<>();

    public CliStreamPreviewRenderer(PrintStream out, boolean enabled,
                                    int maxChars, boolean showReasoning) {
        this.out = out;
        this.enabled = enabled;
        this.maxChars = maxChars;
        this.showReasoning = showReasoning;
        this.ansiSupported = detectAnsi();
    }

    /** 注入 JLine3 Terminal 以获得可靠的终端光标控制。 */
    public void setJlineTerminal(org.jline.terminal.Terminal terminal) {
        this.jlineTerminal = terminal;
    }

    /** 工厂方法：从 AppConfig 创建。 */
    public static CliStreamPreviewRenderer fromConfig(
            com.gsim.app.AppConfig config,
            PrintStream out) {
        return new CliStreamPreviewRenderer(
                out,
                config.isCliStreamPreviewEnabled(),
                config.getCliStreamPreviewMaxChars(),
                config.isCliStreamPreviewShowReasoning());
    }

    /** 追加 content delta 并渲染灰框。 */
    public void appendContent(String streamId, String text) {
        if (!enabled || !ansiSupported || text == null || text.isEmpty()) return;
        contentBufs.computeIfAbsent(streamId, k -> new StringBuilder()).append(text);
        renderInternal(streamId);
    }

    /** 追加 reasoning delta 并渲染灰框。 */
    public void appendReasoning(String streamId, String text) {
        if (!enabled || !ansiSupported || !showReasoning || text == null || text.isEmpty()) return;
        reasoningBufs.computeIfAbsent(streamId, k -> new StringBuilder()).append(text);
        renderInternal(streamId);
    }

    /** 标记 tool_call delta 并渲染灰框。 */
    public void markToolCallDelta(String streamId) {
        if (!enabled || !ansiSupported) return;
        toolCallDeltaCounts.merge(streamId, 1, Integer::sum);
        renderInternal(streamId);
    }

    /** 绘制"等待输出……"框。 */
    public void renderWaiting(String streamId) {
        if (!enabled || !ansiSupported) return;
        clearPreviousBox(true);
        int lineCount = 0;
        printGrey(TL + H + " LLM 正在输出 " + repeat(H, BOX_WIDTH - 13) + TR);
        println(); lineCount++;
        printGrey(V + " 等待输出……" + repeat(" ", BOX_WIDTH - 12) + V);
        println(); lineCount++;
        printGrey(BL + repeat(H, BOX_WIDTH - 2) + BR);
        println(); lineCount++;
        resolveWriter().flush();
        lastPrintedLines = lineCount;
        lastStreamId = streamId;
    }

    /** 绘制"正在选择工具……"框。 */
    public void renderToolChoosing(String streamId) {
        if (!enabled || !ansiSupported) return;
        clearPreviousBox(true);
        int lineCount = 0;
        printGrey(TL + H + " LLM 正在输出 " + repeat(H, BOX_WIDTH - 13) + TR);
        println(); lineCount++;
        printGrey(V + " 正在选择工具……" + repeat(" ", BOX_WIDTH - 14) + V);
        println(); lineCount++;
        printGrey(BL + repeat(H, BOX_WIDTH - 2) + BR);
        println(); lineCount++;
        resolveWriter().flush();
        lastPrintedLines = lineCount;
        lastStreamId = streamId;
    }

    /**
     * 清除灰框（流式结束后调用）。
     * 擦除旧框，清理该 stream 的所有缓冲区。
     */
    public void clear(String streamId) {
        clearPreviousBox(false);
        lastPrintedLines = 0;
        lastStreamId = null;
        // 清理缓冲区
        contentBufs.remove(streamId);
        reasoningBufs.remove(streamId);
        toolCallDeltaCounts.remove(streamId);
    }

    /**
     * 如果当前有活跃灰框，则清除。
     * 不清理缓冲区（流可能还在进行中，非流事件打断后仍需恢复）。
     */
    public void clearIfActive() {
        if (lastPrintedLines > 0) {
            clearPreviousBox(false);
            lastPrintedLines = 0;
            lastStreamId = null;
        }
    }

    /** 是否启用。 */
    public boolean isEnabled() {
        return enabled && ansiSupported;
    }

    // ---- 内部渲染 ----

    private void renderInternal(String streamId) {
        clearPreviousBox(true);

        String content = getBuf(contentBufs, streamId);
        String reasoning = getBuf(reasoningBufs, streamId);

        boolean hasReasoning = showReasoning && !reasoning.isEmpty();
        boolean hasContent = !content.isEmpty();
        boolean hasToolCalls = toolCallDeltaCounts.getOrDefault(streamId, 0) > 0;
        boolean hasAny = hasReasoning || hasContent || hasToolCalls;

        int lineCount = 0;

        // 顶边框
        printGrey(TL + H + " LLM 正在输出 " + repeat(H, BOX_WIDTH - 13) + TR);
        println(); lineCount++;

        if (hasReasoning) {
            for (String rl : wrapLines(truncate(reasoning, maxChars), BOX_WIDTH - 6)) {
                printGrey(V + " 思考：" + padRight(rl, BOX_WIDTH - 6) + " " + V);
                println(); lineCount++;
            }
        }

        if (hasReasoning && hasContent) {
            printGrey(V + repeat(" ", BOX_WIDTH - 2) + V);
            println(); lineCount++;
        }

        if (hasContent) {
            for (String cl : wrapLines(truncate(content, maxChars), BOX_WIDTH - 6)) {
                printGrey(V + " 输出：" + padRight(cl, BOX_WIDTH - 6) + " " + V);
                println(); lineCount++;
            }
        }

        if (!hasAny) {
            printGrey(V + " 等待输出……" + repeat(" ", BOX_WIDTH - 12) + V);
            println(); lineCount++;
        } else if (hasToolCalls && !hasReasoning && !hasContent) {
            printGrey(V + " 正在选择工具……" + repeat(" ", BOX_WIDTH - 14) + V);
            println(); lineCount++;
        }

        printGrey(BL + repeat(H, BOX_WIDTH - 2) + BR);
        println(); lineCount++;

        resolveWriter().flush();
        lastPrintedLines = lineCount;
        lastStreamId = streamId;
    }

    // ---- 内部 helpers ----

    private static String getBuf(Map<String, StringBuilder> bufs, String id) {
        StringBuilder sb = bufs.get(id);
        return sb != null ? sb.toString() : "";
    }

    /**
     * 清除旧灰框。
     * <ol>
     *   <li>CPL ({@code \033[NF]}) 回到旧框第一行</li>
     *   <li>{@code \033[2K} 逐行物理擦除（不依赖后续输出宽度）</li>
     *   <li>可选回位到框开头，供下一轮重绘使用</li>
     * </ol>
     *
     * @param repositionToTop true=擦完回到框开头（供 renderInternal 原地重绘）
     *                        false=留在框末尾下一行（供 clear/clearIfActive 后自然续写）
     */
    private void clearPreviousBox(boolean repositionToTop) {
        if (lastPrintedLines <= 0) return;
        if (!ansiSupported) return;

        var writer = resolveWriter();
        // 1. 回到旧框第一行
        writer.print("\033[" + lastPrintedLines + "F");

        // 2. 逐行擦除
        for (int i = 0; i < lastPrintedLines; i++) {
            writer.print("\033[2K");                          // 清除整行
            if (i < lastPrintedLines - 1) {
                writer.print("\033[1B");                       // 下一行
            }
        }

        // 3. 可选：回到框开头（供 renderInternal 重绘）
        if (repositionToTop && lastPrintedLines > 1) {
            writer.print("\033[" + (lastPrintedLines - 1) + "F");
        }
        writer.flush();
    }

    private void printGrey(String text) {
        if (!ansiSupported) return;
        var w = resolveWriter();
        w.print(ANSI_GREY + text + ANSI_RESET);
    }

    /** 统一输出通道：有 JLine Terminal 时走 terminal writer，否则走缓存的 PrintWriter。 */
    private java.io.PrintWriter resolveWriter() {
        if (jlineTerminal != null) {
            return jlineTerminal.writer();
        }
        if (cachedWriter == null) {
            cachedWriter = new java.io.PrintWriter(out, true);
        }
        return cachedWriter;
    }

    private void println() {
        resolveWriter().println();
    }

    private String truncate(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(s.length() - maxChars);
    }

    private static String[] wrapLines(String text, int width) {
        if (text == null || text.isEmpty()) return new String[]{""};
        int lineCount = (text.length() + width - 1) / width;
        String[] lines = new String[lineCount];
        for (int i = 0; i < lineCount; i++) {
            int start = i * width;
            int end = Math.min(start + width, text.length());
            lines[i] = text.substring(start, end);
        }
        return lines;
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + repeat(" ", width - s.length());
    }

    private static String repeat(String s, int count) {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    private static boolean detectAnsi() {
        String term = System.getenv("TERM");
        if (term == null) {
            return System.console() != null;
        }
        return !"dumb".equals(term);
    }
}
