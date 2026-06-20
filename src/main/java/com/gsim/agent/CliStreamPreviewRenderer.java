package com.gsim.agent;

import java.io.PrintStream;

/**
 * CLI 流式预览渲染器 — 从 {@link LlmStreamSnapshot} 渲染 ANSI 灰框。
 *
 * <p>不再维护内部 buffer — 状态由 {@link LlmStreamStateRegistry} 管理。
 * 每次 render 传入当前 snapshot 即可。
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

    // 上一次渲染的行数，用于清除旧框
    private int lastPrintedLines = 0;
    private String lastStreamId = null;

    public CliStreamPreviewRenderer(PrintStream out, boolean enabled,
                                    int maxChars, boolean showReasoning) {
        this.out = out;
        this.enabled = enabled;
        this.maxChars = maxChars;
        this.showReasoning = showReasoning;
        this.ansiSupported = detectAnsi();
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

    /**
     * 根据 snapshot 渲染灰框。
     * 自动清除上一次渲染的内容。
     */
    public void render(LlmStreamSnapshot snapshot) {
        if (!enabled || !ansiSupported) return;
        if (snapshot == null) snapshot = LlmStreamSnapshot.EMPTY;

        // 清除之前的灰框
        clearPreviousBox();

        // 不活跃的 stream 不渲染（已完成/失败/未开始）
        if (!snapshot.active()) {
            lastPrintedLines = 0;
            lastStreamId = null;
            return;
        }

        int lineCount = 0;

        // 顶边框
        printGrey(TL + H + " LLM 正在输出 " + repeat(H, BOX_WIDTH - 13) + TR);
        out.println();
        lineCount++;

        String reasoning = truncate(snapshot.reasoning(), maxChars);
        String content = truncate(snapshot.content(), maxChars);

        boolean hasReasoning = reasoning != null && !reasoning.isEmpty();
        boolean hasContent = content != null && !content.isEmpty();
        boolean hasToolCalls = snapshot.toolCallDeltaCount() > 0;
        boolean hasAny = hasReasoning || hasContent || hasToolCalls;

        if (showReasoning && hasReasoning) {
            for (String rl : wrapLines(reasoning, BOX_WIDTH - 6)) {
                printGrey(V + " 思考：" + padRight(rl, BOX_WIDTH - 6) + " " + V);
                out.println();
                lineCount++;
            }
        }

        // 分隔线（两个区都有内容时）
        if (showReasoning && hasReasoning && hasContent) {
            printGrey(V + repeat(" ", BOX_WIDTH - 2) + V);
            out.println();
            lineCount++;
        }

        if (hasContent) {
            for (String cl : wrapLines(content, BOX_WIDTH - 6)) {
                printGrey(V + " 输出：" + padRight(cl, BOX_WIDTH - 6) + " " + V);
                out.println();
                lineCount++;
            }
        }

        if (!hasAny) {
            // 三者全空 → 等待
            printGrey(V + " 等待输出……" + repeat(" ", BOX_WIDTH - 12) + V);
            out.println();
            lineCount++;
        } else if (hasToolCalls && !hasReasoning && !hasContent) {
            // 只有 tool calls → 正在选择工具
            printGrey(V + " 正在选择工具……" + repeat(" ", BOX_WIDTH - 14) + V);
            out.println();
            lineCount++;
        }

        // 底边框
        printGrey(BL + repeat(H, BOX_WIDTH - 2) + BR);
        out.println();
        lineCount++;

        out.flush();
        lastPrintedLines = lineCount;
        lastStreamId = snapshot.streamId();
    }

    /**
     * 清除灰框（流式结束后由 CliAgentProgressSink 调用）。
     * 不再依赖内部 active 状态 — 直接擦除上次渲染。
     */
    public void clear(String streamId) {
        clearPreviousBox();
        lastPrintedLines = 0;
        lastStreamId = null;
    }

    /** 是否启用。 */
    public boolean isEnabled() {
        return enabled && ansiSupported;
    }

    // ---- 内部 ----

    private void clearPreviousBox() {
        if (ansiSupported && lastPrintedLines > 0) {
            for (int i = 0; i < lastPrintedLines; i++) {
                out.print("\033[1A");
                out.print("\033[2K");
            }
            out.flush();
        }
    }

    private void printGrey(String text) {
        if (ansiSupported) {
            out.print(ANSI_GREY + text + ANSI_RESET);
        }
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
