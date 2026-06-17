package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;

/**
 * /help — 显示所有可用命令及其说明。
 */
public class HelpCommand implements InteractionCommand {

    // 所有命令的静态注册表（由 InteractionManager 注入时填充）
    private final java.util.function.Supplier<java.util.Map<String, InteractionCommand>> commandSupplier;

    public HelpCommand(java.util.function.Supplier<java.util.Map<String, InteractionCommand>> commandSupplier) {
        this.commandSupplier = commandSupplier;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "显示所有可用命令及其说明";
    }

    @Override
    public String usage() {
        return "/help";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        var commands = commandSupplier.get();
        var sb = new StringBuilder();
        sb.append("========== GSimulator 命令列表 ==========\n\n");

        for (var entry : commands.entrySet()) {
            InteractionCommand cmd = entry.getValue();
            sb.append(String.format("  /%-15s — %s\n", cmd.name(), cmd.description()));
            sb.append(String.format("  %-17s   用法: %s\n\n", "", cmd.usage()));
        }

        sb.append("=========================================\n");
        sb.append("输入命令时不需要尖括号，直接输入实际内容。\n");

        return InteractionResult.ok(sb.toString());
    }
}
