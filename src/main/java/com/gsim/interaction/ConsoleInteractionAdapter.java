package com.gsim.interaction;

import com.gsim.commands.ChatCommand;
import com.gsim.commands.NodeCommand;
import com.gsim.commands.WorldCommand;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CLI 交互适配器 — 处理终端输入输出。
 * 默认使用 JLine（支持历史、方向键），fallback 到 BufferedReader。
 *
 * <p>命令路由：
 * <ul>
 *   <li>{@code /world}, {@code /node}, {@code /chat} — 路由到对应的命令类</li>
 *   <li>{@code /exit}, {@code /quit} — 关闭 REPL</li>
 *   <li>{@code /help} — 显示帮助</li>
 *   <li>其他输入 — 提示使用 {@code /chat} 发送消息</li>
 * </ul>
 */
public class ConsoleInteractionAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConsoleInteractionAdapter.class);

    private final InteractionManager manager;
    private final InteractionSession session;
    private final PrintStream out;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Path dataDir;

    private WorldCommand worldCommand;
    private NodeCommand nodeCommand;
    private ChatCommand chatCommand;

    private LineReader lineReader;
    private BufferedReader fallbackReader;
    private boolean jlineAvailable;
    private boolean streamEnabled = false;

    public ConsoleInteractionAdapter(InteractionManager manager, InteractionSession session) {
        this(manager, session, null);
    }

    public ConsoleInteractionAdapter(InteractionManager manager, InteractionSession session, Path dataDir) {
        this.manager = manager;
        this.session = session;
        this.dataDir = dataDir;
        this.out = System.out;
        initJline();
    }

    /**
     * Full constructor accepting the new command instances for direct routing.
     */
    public ConsoleInteractionAdapter(InteractionManager manager, InteractionSession session,
                                      Path dataDir,
                                      WorldCommand worldCommand,
                                      NodeCommand nodeCommand,
                                      ChatCommand chatCommand) {
        this(manager, session, dataDir);
        this.worldCommand = worldCommand;
        this.nodeCommand = nodeCommand;
        this.chatCommand = chatCommand;
    }

    /** Set or update the command instances after construction. */
    public void setNewCommands(WorldCommand worldCommand, NodeCommand nodeCommand, ChatCommand chatCommand) {
        this.worldCommand = worldCommand;
        this.nodeCommand = nodeCommand;
        this.chatCommand = chatCommand;
    }

    private void initJline() {
        // 两段式：先尝试正常 terminal，失败再试 dumb，都失败则 BufferedReader
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .build();
            log.debug("JLine: normal terminal acquired");
        } catch (Exception e1) {
            log.debug("JLine: normal terminal unavailable ({})", e1.getMessage());
            try {
                terminal = TerminalBuilder.builder()
                        .system(true)
                        .dumb(true)
                        .build();
                log.debug("JLine: dumb terminal fallback acquired");
            } catch (Exception e2) {
                log.warn("JLine unavailable (normal: {}, dumb: {}), falling back to BufferedReader",
                        e1.getMessage(), e2.getMessage());
            }
        }

        if (terminal != null) {
            try {
                Path historyFile = resolveHistoryFile();
                lineReader = LineReaderBuilder.builder()
                        .terminal(terminal)
                        .variable(LineReader.HISTORY_FILE, historyFile)
                        .build();
                jlineAvailable = true;
                log.debug("JLine initialized, history: {}", historyFile);
            } catch (Exception e) {
                log.warn("JLine LineReader build failed: {}", e.getMessage());
                this.fallbackReader = new BufferedReader(new InputStreamReader(System.in));
                jlineAvailable = false;
            }
        } else {
            this.fallbackReader = new BufferedReader(new InputStreamReader(System.in));
            jlineAvailable = false;
        }
    }

    private Path resolveHistoryFile() {
        if (dataDir != null) {
            try {
                Files.createDirectories(dataDir);
                return dataDir.resolve(".gsim_history");
            } catch (Exception ignored) {}
        }
        return Path.of(System.getProperty("user.home"), ".gsim_history");
    }

    /** 暴露 JLine3 Terminal（供需要直接操作终端的光标控制组件使用）。 */
    public org.jline.terminal.Terminal getJlineTerminal() {
        if (lineReader != null) {
            return lineReader.getTerminal();
        }
        return null;
    }

    /** 设置流式输出模式。流式开启时，LLM 回复已在流式过程中直接输出，无需再次打印。 */
    public void setStreamEnabled(boolean streamEnabled) {
        this.streamEnabled = streamEnabled;
    }

    public String buildPrompt() {
        return "gsim> ";
    }

    public void start() {
        printWelcome();

        while (running.get()) {
            try {
                String prompt = buildPrompt();
                String line = readLine(prompt);

                if (line == null) { shutdown(); break; }

                // 清洗输入
                String cleaned = CliInputSanitizer.sanitize(line);
                if (cleaned.isEmpty()) continue;

                // / 开头 → 命令模式
                if (cleaned.startsWith("/")) {
                    String cmdName = extractCommandName(cleaned);
                    if ("exit".equals(cmdName) || "quit".equals(cmdName)) {
                        shutdown();
                        break;
                    }
                    handleCommand(cmdName, cleaned);
                } else {
                    // 非命令 → 建议使用 /chat 发送消息
                    out.println("直接输入文本需要通过 /chat 命令发送。");
                    out.println("输入 /chat <message> 发送消息，/help 查看可用命令。");
                    out.println();
                }

            } catch (IOException e) {
                log.error("REPL read error: {}", e.getMessage(), e);
                out.println("读取输入时发生错误: " + e.getMessage());
            }
        }
    }

    /**
     * Route a slash command to the appropriate handler.
     */
    private void handleCommand(String cmdName, String rawInput) {
        List<String> args = parseArgs(rawInput);

        switch (cmdName) {
            case "help" -> {
                printHelp();
                return;
            }
            case "world" -> {
                if (worldCommand == null) {
                    out.println("World command not available.");
                    out.println();
                    return;
                }
                String text = worldCommand.execute(args);
                displayResult(InteractionResult.ok(text));
            }
            case "node" -> {
                if (nodeCommand == null) {
                    out.println("Node command not available.");
                    out.println();
                    return;
                }
                String text = nodeCommand.execute(args);
                displayResult(InteractionResult.ok(text));
            }
            case "chat" -> {
                if (chatCommand == null) {
                    out.println("Chat command not available.");
                    out.println();
                    return;
                }
                String text = chatCommand.execute(args);
                displayResult(InteractionResult.ok(text));
            }
            default -> displayResult(
                    InteractionResult.fail("Unknown command: /" + cmdName
                            + ". Type /help for available commands."));
        }
    }

    /** Split raw input into arg list (skipping the command prefix). */
    private List<String> parseArgs(String rawInput) {
        String trimmed = rawInput.trim();
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx < 0) return List.of();
        String rest = trimmed.substring(spaceIdx + 1).trim();
        if (rest.isEmpty()) return List.of();
        return Arrays.asList(rest.split("\\s+"));
    }

    private void printHelp() {
        out.println("Available commands:");
        out.println("  /world [list|create|switch]   — World management");
        out.println("  /node [status|list|goto|create] — Node management");
        out.println("  /chat <message>               — Send message to LLM");
        out.println("  /chat history [n]             — Show last n messages");
        out.println("  /chat clear                   — Clear chat session");
        out.println("  /exit                         — Exit");
        out.println("  /help                         — Show this help");
        out.println();
    }

    private String readLine(String prompt) throws IOException {
        if (jlineAvailable && lineReader != null) {
            try {
                return lineReader.readLine(prompt);
            } catch (EndOfFileException | UserInterruptException e) {
                return null; // EOF / Ctrl+C
            } catch (Exception e) {
                log.warn("JLine read error, falling back: {}", e.getMessage());
                jlineAvailable = false;
                fallbackReader = new BufferedReader(new InputStreamReader(System.in));
                out.print(prompt);
                out.flush();
                return fallbackReader.readLine();
            }
        } else if (fallbackReader != null) {
            out.print(prompt);
            out.flush();
            return fallbackReader.readLine();
        }
        return null;
    }

    public void shutdown() { running.set(false); }
    public boolean isRunning() { return running.get(); }

    private void printWelcome() {
        String campaignId = session.getContext().getCurrentCampaignId();
        String turnId = session.getContext().getCurrentTurnId();
        out.println("GSimulator started.");
        out.println("Current campaign: " + (campaignId != null ? campaignId : "(未初始化)"));
        out.println("Current turn: " + (turnId != null ? turnId : "(未初始化)"));
        out.println();
        out.println("输入 /help 查看可用命令，直接输入文本与 Agent 对话，/exit 退出。");
        out.println();
    }

    private void displayResult(InteractionResult result) {
        if (!streamEnabled) {
            if (result.displayText() != null && !result.displayText().isBlank()) out.println(result.displayText());
        }
        if (result.errors() != null && !result.errors().isEmpty())
            for (String err : result.errors()) out.println("[ERROR] " + err);
        out.println();
    }

    private String extractCommandName(String rawInput) {
        if (rawInput == null || !rawInput.startsWith("/")) return "";
        return rawInput.trim().substring(1).split("\\s+", 2)[0].toLowerCase();
    }
}
