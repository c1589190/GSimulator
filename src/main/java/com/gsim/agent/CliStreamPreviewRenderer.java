package com.gsim.agent;

import java.io.PrintStream;

/**
 * CLI 流式预览渲染器 — 在 LLM 流式输出时显示灰色框。
 *
 * <p>使用 ANSI 转义码实现灰色边框，实时显示 reasoning 和 content 分区。
 * 如果终端不支持 ANSI，退化为不显示灰框。
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

    private static final String ANSI_GREY = "[90m";
    private static final String ANSI_RESET = "[0m";

    // 框字符
    private static final String TL = "┌"; // ┌
    private static final String TR = "┐"; // ┐
    private static final String BL = "└"; // └
    private static final String BR = "┘"; // ┘
    private static final String H = "─";  // ─
    private static final String V = "│";  // │

    private static final int BOX_WIDTH = 72;

    private final PrintStream out;
    private final boolean enabled;
    private final int maxChars;
    private final boolean showReasoning;
    private final boolean ansiSupported;

    private final StringBuilder reasoningBuf = new StringBuilder();
    private final StringBuilder contentBuf = new StringBuilder();
    private boolean active = false;
    private int lastPrintedLines = 0;

    /**
     * @param out           输出流
     * @param enabled       是否启用灰框
     * @param maxChars      灰框内最多显示字符数
     * @param showReasoning 是否显示 reasoning 区
     */
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
     * 开始流式预览。
     */
    public void start() {
        if (!enabled || !ansiSupported) return;
        active = true;
        reasoningBuf.setLength(0);
        contentBuf.setLength(0);
        lastPrintedLines = 0;
        render();
    }

    /**
     * 追加 reasoning delta。
     */
    public void appendReasoning(String delta) {
        if (!active) return;
        reasoningBuf.append(delta);
        truncate(reasoningBuf);
        render();
    }

    /**
     * 追加 content delta。
     */
    public void appendContent(String delta) {
        if (!active) return;
        contentBuf.append(delta);
        truncate(contentBuf);
        render();
    }

    /**
     * 清除灰框（流式结束或失败后）。
     */
    public void clear() {
        if (!active) return;
        active = false;

        // 清除灰框行
        if (ansiSupported && lastPrintedLines > 0) {
            // 上移光标并清除
            for (int i = 0; i < lastPrintedLines; i++) {
                out.print("[1A"); // 上移一行
                out.print("[2K"); // 清除当前行
            }
            out.flush();
        }
        reasoningBuf.setLength(0);
        contentBuf.setLength(0);
        lastPrintedLines = 0;
    }

    /** 是否正在显示灰框。 */
    public boolean isActive() {
        return active;
    }

    // ---- 内部 ----

    private void render() {
        if (!active || !ansiSupported) return;

        // 清除之前的灰框
        if (lastPrintedLines > 0) {
            for (int i = 0; i < lastPrintedLines; i++) {
                out.print("[1A"); // 上移一行
                out.print("[2K"); // 清除当前行
            }
        }

        int lineCount = 0;

        // 顶边框
        printGrey(TL + H + " LLM 正在输出 " + repeat(H, BOX_WIDTH - 13) + TR);
        out.println();
        lineCount++;

        // reasoning 区
        if (showReasoning && reasoningBuf.length() > 0) {
            String[] reasonLines = wrapLines(reasoningBuf.toString(), BOX_WIDTH - 6);
            for (String rl : reasonLines) {
                printGrey(V + " 思考：" + padRight(rl, BOX_WIDTH - 6) + " " + V);
                out.println();
                lineCount++;
            }
        }

        // 分隔线（如果两个区都有内容）
        if (showReasoning && reasoningBuf.length() > 0 && contentBuf.length() > 0) {
            printGrey(V + repeat(" ", BOX_WIDTH - 2) + V);
            out.println();
            lineCount++;
        }

        // content 区
        if (contentBuf.length() > 0) {
            String[] contentLines = wrapLines(contentBuf.toString(), BOX_WIDTH - 6);
            for (String cl : contentLines) {
                printGrey(V + " 输出：" + padRight(cl, BOX_WIDTH - 6) + " " + V);
                out.println();
                lineCount++;
            }
        }

        if (reasoningBuf.length() == 0 && contentBuf.length() == 0) {
            printGrey(V + " 等待输出……" + repeat(" ", BOX_WIDTH - 12) + V);
            out.println();
            lineCount++;
        }

        // 底边框
        printGrey(BL + repeat(H, BOX_WIDTH - 2) + BR);
        out.println();
        lineCount++;

        out.flush();
        lastPrintedLines = lineCount;
    }

    private void printGrey(String text) {
        if (ansiSupported) {
            out.print(ANSI_GREY + text + ANSI_RESET);
        }
    }

    private void truncate(StringBuilder buf) {
        if (buf.length() > maxChars) {
            int start = buf.length() - maxChars;
            buf.delete(0, start);
        }
    }

    /** 简单折行：按宽度截断。 */
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
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 简单 ANSI 检测：检查 TERM 环境变量。
     * 不依赖 System.console()（在 IDE/PTY/容器中可能返回 null）。
     * 只要 TERM 设置且不是 "dumb" 就认为支持 ANSI。
     */
    private static boolean detectAnsi() {
        String term = System.getenv("TERM");
        if (term == null) {
            // TERM 未设置，尝试 System.console() 作为 fallback
            return System.console() != null;
        }
        if ("dumb".equals(term)) return false;
        // TERM 已设置且不是 dumb，认定支持 ANSI
        return true;
    }
}
