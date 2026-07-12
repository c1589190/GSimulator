package com.gsim.interaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 交互管理器 — 命令解析、路由、执行的核心协调者。
 * 不负责 I/O，输入输出由 InteractionAdapter 处理。
 */
public class InteractionManager {

    private static final Logger log = LoggerFactory.getLogger(InteractionManager.class);

    private final Map<String, InteractionCommand> commands = new LinkedHashMap<>();
    private final CommandParser parser;

    public InteractionManager() {
        this.parser = new CommandParser();
    }

    /**
     * 注册命令。
     */
    public void registerCommand(InteractionCommand command) {
        commands.put(command.name(), command);
        log.debug("Registered command: /{}", command.name());
    }

    /**
     * 获取所有已注册的命令。
     */
    public Map<String, InteractionCommand> getCommands() {
        return Map.copyOf(commands);
    }

    /**
     * 处理用户输入并返回结果。
     * @param rawInput 原始用户输入
     * @param session 当前会话
     * @return 交互结果
     */
    public InteractionResult handle(String rawInput, InteractionSession session) {
        if (rawInput == null || rawInput.isBlank()) {
            return InteractionResult.ok("");
        }

        CommandParser.ParsedCommand parsed = parser.parse(rawInput);

        if (!parsed.isCommand()) {
            return InteractionResult.fail("Unknown input. Type /help for available commands.");
        }

        InteractionCommand command = commands.get(parsed.commandName());
        if (command == null) {
            return InteractionResult.fail(
                    "Unknown command: /" + parsed.commandName() + ". Type /help for available commands.");
        }

        try {
            return command.execute(parsed.args(), session);
        } catch (Exception e) {
            log.error("Command /{} failed: {}", parsed.commandName(), e.getMessage(), e);
            return InteractionResult.fail("Command /" + parsed.commandName() + " failed: " + e.getMessage());
        }
    }
}
