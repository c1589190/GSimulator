package com.gsim.interaction;

import com.gsim.chat.NodeAgentChatService;
import com.gsim.interaction.commands.ChatCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CLI 交互适配器 — 处理终端输入输出。
 * 非 / 开头的输入自动路由到 NodeAgentChatService。
 */
public class ConsoleInteractionAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConsoleInteractionAdapter.class);

    private final InteractionManager manager;
    private final InteractionSession session;
    private final BufferedReader reader;
    private final PrintStream out;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final String prompt = "gsim> ";

    private NodeAgentChatService chatService;
    private ChatCommand chatCommand;

    public ConsoleInteractionAdapter(InteractionManager manager, InteractionSession session) {
        this.manager = manager;
        this.session = session;
        this.reader = new BufferedReader(new InputStreamReader(System.in));
        this.out = System.out;
    }

    /** 注入对话服务（在注册命令后调用）。 */
    public void setChatService(NodeAgentChatService chatService, ChatCommand chatCommand) {
        this.chatService = chatService;
        this.chatCommand = chatCommand;
    }

    public void start() {
        printWelcome();

        while (running.get()) {
            try {
                out.print(prompt);
                out.flush();
                String line = reader.readLine();

                if (line == null) { shutdown(); break; }
                if (line.isBlank()) continue;

                // /messages 特殊处理
                if (line.trim().startsWith("/messages") && chatCommand != null) {
                    String afterCmd = line.trim().substring("/messages".length()).trim();
                    InteractionResult r = chatCommand.handleMessages(
                            new String[]{afterCmd.isEmpty() ? "" : afterCmd});
                    displayResult(r);
                    continue;
                }

                // 非 / 开头 → 对话模式
                if (!line.trim().startsWith("/") && chatService != null) {
                    try {
                        String reply = chatService.chat(line.trim());
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
                InteractionResult result = manager.handle(line, session);
                displayResult(result);

                if ("exit".equals(extractCommandName(line))) break;

            } catch (IOException e) {
                log.error("REPL read error: {}", e.getMessage(), e);
                out.println("读取输入时发生错误: " + e.getMessage());
            }
        }
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
