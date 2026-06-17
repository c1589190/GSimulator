package com.gsim.interaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CLI 交互适配器 — 处理终端输入输出。
 * 通过 InteractionManager 处理命令，不直接写业务逻辑。
 */
public class ConsoleInteractionAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConsoleInteractionAdapter.class);

    private final InteractionManager manager;
    private final InteractionSession session;
    private final BufferedReader reader;
    private final PrintStream out;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final String prompt = "gsim> ";

    public ConsoleInteractionAdapter(InteractionManager manager, InteractionSession session) {
        this.manager = manager;
        this.session = session;
        this.reader = new BufferedReader(new InputStreamReader(System.in));
        this.out = System.out;
    }

    /**
     * 启动 REPL 循环。
     */
    public void start() {
        printWelcome();

        while (running.get()) {
            try {
                out.print(prompt);
                out.flush();
                String line = reader.readLine();

                if (line == null) {
                    // EOF (Ctrl+D)
                    shutdown();
                    break;
                }

                if (line.isBlank()) {
                    continue;
                }

                InteractionResult result = manager.handle(line, session);
                displayResult(result);

                // 检查是否应该退出
                if ("exit".equals(extractCommandName(line))) {
                    break;
                }

            } catch (IOException e) {
                log.error("REPL read error: {}", e.getMessage(), e);
                out.println("读取输入时发生错误: " + e.getMessage());
            }
        }
    }

    /**
     * 请求关闭 REPL。
     */
    public void shutdown() {
        running.set(false);
    }

    /**
     * 检查是否在运行。
     */
    public boolean isRunning() {
        return running.get();
    }

    private void printWelcome() {
        String campaignId = session.getContext().getCurrentCampaignId();
        String turnId = session.getContext().getCurrentTurnId();
        out.println("GSimulator started.");
        out.println("Current campaign: " + (campaignId != null ? campaignId : "(未初始化)"));
        out.println("Current turn: " + (turnId != null ? turnId : "(未初始化)"));
        out.println();
        out.println("输入 /help 查看可用命令，/exit 退出。");
        out.println();
    }

    private void displayResult(InteractionResult result) {
        if (result.displayText() != null && !result.displayText().isBlank()) {
            out.println(result.displayText());
        }
        if (result.errors() != null && !result.errors().isEmpty()) {
            for (String err : result.errors()) {
                out.println("[ERROR] " + err);
            }
        }
        out.println();
    }

    private String extractCommandName(String rawInput) {
        if (rawInput == null || !rawInput.startsWith("/")) {
            return "";
        }
        String withoutSlash = rawInput.trim().substring(1);
        return withoutSlash.split("\\s+", 2)[0].toLowerCase();
    }
}
