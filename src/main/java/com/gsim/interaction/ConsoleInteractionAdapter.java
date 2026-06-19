package com.gsim.interaction;

import com.gsim.chat.NodeAgentChatService;
import com.gsim.data.DataManager;
import com.gsim.interaction.commands.ChatCommand;
import com.gsim.interaction.commands.MessagesCommand;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * CLI 交互适配器 — 处理终端输入输出。
 * 默认使用 JLine（支持历史、方向键），fallback 到 BufferedReader。
 * 非 / 开头的输入自动路由到 NodeAgentChatService。
 */
public class ConsoleInteractionAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConsoleInteractionAdapter.class);

    private final InteractionManager manager;
    private final InteractionSession session;
    private final PrintStream out;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Supplier<DataManager> dmSupplier;

    private LineReader lineReader;
    private BufferedReader fallbackReader;
    private boolean jlineAvailable;

    private NodeAgentChatService chatService;
    private ChatCommand chatCommand;
    private MessagesCommand messagesCommand;

    public ConsoleInteractionAdapter(InteractionManager manager, InteractionSession session) {
        this(manager, session, () -> null);
    }

    /** 构造函数注入 DataManager supplier 用于动态 prompt。 */
    public ConsoleInteractionAdapter(InteractionManager manager, InteractionSession session, Supplier<DataManager> dmSupplier) {
        this.manager = manager;
        this.session = session;
        this.dmSupplier = dmSupplier;
        this.out = System.out;
        initJline();
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
        // 优先使用 data/.gsim_history
        DataManager dm = dmSupplier.get();
        if (dm != null) {
            try {
                Path dataRoot = dm.getDataRoot();
                if (dataRoot != null) {
                    Files.createDirectories(dataRoot);
                    return dataRoot.resolve(".gsim_history");
                }
            } catch (Exception ignored) {}
        }
        // fallback: home dir
        return Path.of(System.getProperty("user.home"), ".gsim_history");
    }

    /** 注入对话服务（在注册命令后调用）。 */
    public void setChatService(NodeAgentChatService chatService, ChatCommand chatCommand,
                               MessagesCommand messagesCommand) {
        this.chatService = chatService;
        this.chatCommand = chatCommand;
        this.messagesCommand = messagesCommand;
    }

    /** 生成动态 prompt。 */
    public String buildPrompt() {
        DataManager dm = dmSupplier.get();
        if (dm == null || dm.needsRootBootstrap()) {
            return "gsim[no-root]> ";
        }
        String rootId = dm.getActiveRootId();
        String branchId = dm.getActiveBranch();
        // 缩短显示：branch.b0000-start → b0000-start
        String shortBranch = branchId != null ? branchId.replace("branch.", "") : "?";
        return "gsim[" + (rootId != null ? rootId : "?") + " " + shortBranch + "]> ";
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

                // /messages — 优先使用正式 MessagesCommand
                if (cleaned.startsWith("/messages") && messagesCommand != null) {
                    String afterCmd = cleaned.substring("/messages".length()).trim();
                    InteractionResult r = messagesCommand.handleRaw(afterCmd);
                    displayResult(r);
                    continue;
                }

                // 非 / 开头 → 对话模式
                if (!cleaned.startsWith("/") && chatService != null) {
                    try {
                        String reply = chatService.chat(cleaned);
                        out.println(reply);
                        out.println();
                    } catch (Exception e) {
                        log.error("Chat error: {}", e.getMessage(), e);
                        out.println("[ERROR] Chat failed: " + e.getMessage());
                        out.println();
                    }
                    continue;
                }

                // / 开头 → 命令模式
                InteractionResult result = manager.handle(cleaned, session);
                displayResult(result);

                if ("exit".equals(extractCommandName(cleaned))) break;

            } catch (IOException e) {
                log.error("REPL read error: {}", e.getMessage(), e);
                out.println("读取输入时发生错误: " + e.getMessage());
            }
        }
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
        if (result.displayText() != null && !result.displayText().isBlank()) out.println(result.displayText());
        if (result.errors() != null && !result.errors().isEmpty())
            for (String err : result.errors()) out.println("[ERROR] " + err);
        out.println();
    }

    private String extractCommandName(String rawInput) {
        if (rawInput == null || !rawInput.startsWith("/")) return "";
        return rawInput.trim().substring(1).split("\\s+", 2)[0].toLowerCase();
    }
}
