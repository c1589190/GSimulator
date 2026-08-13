package com.gsim.session;

/**
 * CLI 节点渲染器 — 将 SessionNode 事件转换为终端格式化文本。
 *
 * <p>同时服务于 ConsoleInteractionAdapter（真实 CLI）和 CliWebSocketServer（WebSocket CLI 镜像），
 * 确保两者输出完全一致。
 *
 * <p>用法：
 * <pre>{@code
 * CliNodeRenderer r = new CliNodeRenderer(true);  // ansi=true for real CLI
 * String text = r.renderPushed(node);             // onNodePushed → 文本行
 * String delta = r.renderContentDelta(delta);     // onNodeUpdated(content) → 增量文本
 * String status = r.renderStatusChange(node, old, nw); // onStatusChanged → 状态行
 * }</pre>
 */
public final class CliNodeRenderer {

    private static final String ANSI_GREY = "\033[90m";
    private static final String ANSI_BOLD = "\033[1m";
    private static final String ANSI_RESET = "\033[0m";

    private final boolean ansi;
    private boolean reasoningOpen;
    private boolean contentBold;

    /**
     * 创建 CLI 节点渲染器。
     *
     * @param ansi 是否启用 ANSI 转义码；true 表示真实终端输出，false 表示 WebSocket 镜像
     */
    public CliNodeRenderer(boolean ansi) {
        this.ansi = ansi;
    }

    // ===== push 事件 → 文本 =====

    /**
     * 节点入池时生成格式化文本行。
     *
     * <p>根据节点类型分派到具体的渲染方法。
     *
     * @param node 会话节点
     * @return 格式化后的文本行，不含尾部换行
     */
    public String renderPushed(SessionNode node) {
        return switch (node.type()) {
            case USER_INPUT -> renderUserInput(node);
            case TOOL_CALL -> renderToolCallPushed(node);
            case AGENT_MESSAGE -> renderAgentMessage(node);
            case SYSTEM -> renderSystem(node);
            case LLM_STREAMING -> renderLlmStarted(node);
        };
    }

    // ===== update 事件 → 增量文本 =====

    /**
     * 节点内容更新时生成增量文本（LLM delta）。
     *
     * @param delta LLM 输出的增量文本片段
     * @return 带粗体标记的增量文本，如果 delta 为空则返回 null
     */
    public String renderContentDelta(String delta) {
        if (delta == null || delta.isEmpty()) return null;
        if (reasoningOpen) {
            reasoningOpen = false;
            contentBold = false;
        }
        if (!contentBold) {
            contentBold = true;
        }
        return bold(delta);
    }

    /**
     * 生成推理内容增量文本。
     *
     * @param delta 推理过程的增量文本
     * @return 带灰色标记的推理增量文本，如果 delta 为空则返回 null
     */
    public String renderReasoningDelta(String delta) {
        if (delta == null || delta.isEmpty()) return null;
        reasoningOpen = true;
        return grey("Thinking: ") + delta;
    }

    // ===== 状态变更 → 文本 =====

    /**
     * 节点状态变更时生成状态行。
     *
     * <p>针对 LLM_STREAMING 和 TOOL_CALL 节点生成不同的状态提示文本。
     *
     * @param node      发生状态变更的会话节点
     * @param oldStatus 变更前的状态
     * @param newStatus 变更后的状态
     * @return 格式化的状态提示文本，无需处理时返回 null
     */
    public String renderStatusChange(SessionNode node, NodeStatus oldStatus, NodeStatus newStatus) {
        if (node.type() == NodeType.LLM_STREAMING) {
            return switch (newStatus) {
                case STREAMING -> bold(""); // signal to start bold
                case DONE -> {
                    if (reasoningOpen || contentBold) {
                        reasoningOpen = false;
                        contentBold = false;
                        yield reset("");
                    }
                    yield "\n";
                }
                case ERROR -> {
                    reasoningOpen = false;
                    contentBold = false;
                    yield reset("\n") + "[Agent] LLM 流式输出失败: " + node.payload().getOrDefault("error", "未知错误");
                }
                default -> null;
            };
        }
        if (node.type() == NodeType.TOOL_CALL) {
            return switch (newStatus) {
                case STREAMING -> "[Agent] 正在执行工具: " + node.payload().get("tool");
                case DONE -> "[Agent] 工具成功: " + node.payload().get("tool");
                case ERROR -> "[Agent] 工具失败: " + node.payload().get("tool");
                default -> null;
            };
        }
        return null;
    }

    /**
     * 生成 LLM 流开始时的 framing 文本。
     *
     * @return framing 文本 "[...] "
     */
    public String startLlmStream() {
        reasoningOpen = false;
        contentBold = false;
        return "[...] ";
    }

    /**
     * 生成 LLM 流结束时的 framing 文本。
     *
     * @param success 流是否成功完成
     * @return 换行文本，如果存在未闭合的格式标记则先执行 ANSI reset
     */
    public String endLlmStream(boolean success) {
        if (reasoningOpen || contentBold) {
            reasoningOpen = false;
            contentBold = false;
            return reset("") + "\n";
        }
        return "\n";
    }

    /** 重置内部状态（新流开始时调用）。 */
    public void resetStreamState() {
        reasoningOpen = false;
        contentBold = false;
    }

    // ===== 私有渲染方法 =====

    private String renderUserInput(SessionNode node) {
        return "> " + node.payload().getOrDefault("text", "");
    }

    private String renderToolCallPushed(SessionNode node) {
        String tool = (String) node.payload().getOrDefault("tool", "unknown");
        return "[Agent] LLM 选择工具: " + tool;
    }

    private String renderAgentMessage(SessionNode node) {
        String subType = (String) node.payload().get("subType");
        if ("simulation_content".equals(subType)) {
            String title = (String) node.payload().getOrDefault("title", "");
            String body = (String) node.payload().getOrDefault("body", "");
            return "▐ 推文: " + title + "\n" + (body != null ? body : "");
        }
        // 普通消息直接返回内容
        String message = (String) node.payload().get("message");
        return message != null ? stripAnsiForWs(message) : null;
    }

    private String renderSystem(SessionNode node) {
        String message = (String) node.payload().getOrDefault("message", "");
        return "[系统] " + message;
    }

    private String renderLlmStarted(SessionNode node) {
        return null; // handled by startLlmStream() for ANSI framing
    }

    // ===== ANSI 工具方法 =====

    private String bold(String text) {
        return ansi ? ANSI_BOLD + text : text;
    }

    private String grey(String text) {
        return ansi ? ANSI_GREY + text : text;
    }

    private String reset(String text) {
        return ansi ? ANSI_RESET + text : text;
    }

    private String stripAnsiForWs(String s) {
        if (s == null) return null;
        return ansi ? s : s.replaceAll("\033\\[[0-9;]*m", "").trim();
    }
}
