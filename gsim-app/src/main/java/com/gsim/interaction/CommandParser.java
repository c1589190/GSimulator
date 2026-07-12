package com.gsim.interaction;

/**
 * 命令解析器 — 将原始用户输入解析为命令名和参数。
 */
public class CommandParser {

    /**
     * 解析用户输入。
     * @param rawInput 原始输入（如 "/player 张三 行动内容"）
     * @return 解析结果，包含命令名和参数数组
     */
    public ParsedCommand parse(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return new ParsedCommand("", new String[0]);
        }

        String trimmed = rawInput.trim();

        // 不以 / 开头，不是命令
        if (!trimmed.startsWith("/")) {
            return new ParsedCommand("", new String[]{trimmed});
        }

        // 提取命令名和参数
        String withoutSlash = trimmed.substring(1);
        String[] parts = withoutSlash.split("\\s+", 2);

        String commandName = parts[0].toLowerCase();

        if (parts.length == 1) {
            return new ParsedCommand(commandName, new String[0]);
        }

        // 对于 /player 和 /run 等需要保留完整参数的命令，
        // 第一个"单词"之后的全部作为参数
        String rawArgs = parts[1];

        // 特殊处理：对于 /player 命令，第一个空格前是玩家名，后面是内容
        if ("player".equals(commandName)) {
            String[] playerParts = rawArgs.split("\\s+", 2);
            if (playerParts.length == 2) {
                return new ParsedCommand(commandName, new String[]{playerParts[0], playerParts[1]});
            } else {
                return new ParsedCommand(commandName, new String[]{playerParts[0]});
            }
        }

        // 对于 /run, /searchdb, /import 命令，全部剩余文本作为一个参数
        if ("run".equals(commandName) || "searchdb".equals(commandName) || "import".equals(commandName)) {
            return new ParsedCommand(commandName, new String[]{rawArgs});
        }

        // 默认：按空格分割
        return new ParsedCommand(commandName, rawArgs.split("\\s+"));
    }

    /**
     * 解析后的命令。
     */
    public record ParsedCommand(
            String commandName,
            String[] args
    ) {
        public boolean isCommand() {
            return commandName != null && !commandName.isBlank();
        }
    }
}
