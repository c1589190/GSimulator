package com.gsim.interaction;

/**
 * 命令接口 — 所有 CLI/Web 命令必须实现此接口。
 */
public interface InteractionCommand {

    /**
     * 命令名称（不含斜杠），如 "help"、"status"、"player"。
     */
    String name();

    /**
     * 命令描述。
     */
    String description();

    /**
     * 命令用法示例。
     */
    String usage();

    /**
     * 执行命令。
     * @param args 命令参数（已解析，不含命令名本身）
     * @param session 当前交互会话
     * @return 交互结果
     */
    InteractionResult execute(String[] args, InteractionSession session);
}
