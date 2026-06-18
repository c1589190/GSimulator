package com.gsim.interaction.commands;

import com.gsim.player.PlayerProfile;
import com.gsim.player.PlayerProfileManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * /players — 管理玩家档案（players.md）。
 *
 * /player 是行动（写 input.md），/players 是档案（写 players.md）。
 */
public class PlayersCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(PlayersCommand.class);
    private final PlayerProfileManager pm;

    public PlayersCommand(PlayerProfileManager pm) { this.pm = pm; }

    @Override public String name() { return "players"; }
    @Override public String description() { return "管理玩家档案（/player 是行动，/players 是档案）"; }
    @Override public String usage() {
        return "/players list|show <名>|add <名>|set <名> <字段> <内容>|note <名> <内容>|remove <名>|template|raw";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args.length == 0) {
            return list();
        }

        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "list" -> list();
            case "show" -> args.length < 2 ? err("用法: /players show <玩家名>") : show(args[1]);
            case "add" -> args.length < 2 ? err("用法: /players add <玩家名>") : add(args[1]);
            case "set" -> args.length < 4 ? err("用法: /players set <玩家名> <字段> <内容>") : set(args[1], args[2], rest(args, 3));
            case "note" -> args.length < 3 ? err("用法: /players note <玩家名> <内容>") : note(args[1], rest(args, 2));
            case "remove" -> args.length < 2 ? err("用法: /players remove <玩家名>") : remove(args[1]);
            case "import" -> args.length < 2 ? err("用法: /players import <文件路径>") : importFile(args[1]);
            case "export" -> args.length < 2 ? err("用法: /players export <文件路径>") : exportFile(args[1]);
            case "raw" -> raw();
            case "template" -> template();
            case "remove-confirm" -> args.length < 2 ? err("用法: /players remove-confirm <玩家名>") : removeConfirm(args[1]);
            default -> err("未知子命令: " + sub + "。可用: list|show|add|set|note|remove|import|export|raw|template");
        };
    }

    // ---- 子命令 ----

    private InteractionResult list() {
        List<PlayerProfile> profiles = pm.listPlayers();
        if (profiles.isEmpty()) {
            return InteractionResult.ok(
                    "当前 world 无玩家档案。\n使用 /players add <玩家名> 创建，或 /players template 查看模板。\n\n文件: " + pm.getPlayersPath());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("========== 玩家档案列表 (").append(profiles.size()).append(" 人) ==========\n\n");
        for (PlayerProfile p : profiles) {
            sb.append("## ").append(p.name()).append("\n");
            sb.append("  阵营: ").append(p.faction());
            sb.append("  | 身份: ").append(p.identity());
            sb.append("  | 状态: ").append(p.currentStatus()).append("\n\n");
        }
        sb.append("使用 /players show <玩家名> 查看完整档案。");
        return InteractionResult.ok(sb.toString());
    }

    private InteractionResult show(String name) {
        Optional<PlayerProfile> opt = pm.getPlayer(name);
        if (opt.isEmpty()) return err("未找到玩家: " + name);

        PlayerProfile p = opt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("========== ").append(p.name()).append(" ==========\n\n");
        sb.append("类型:     ").append(p.type()).append("\n");
        sb.append("阵营:     ").append(p.faction()).append("\n");
        sb.append("身份:     ").append(p.identity()).append("\n");
        sb.append("控制资源: ").append(p.resources()).append("\n");
        sb.append("公开目标: ").append(p.publicGoal()).append("\n");
        sb.append("隐藏倾向: ").append(p.hiddenTendency()).append("\n");
        sb.append("当前状态: ").append(p.currentStatus()).append("\n");
        sb.append("关系:     ").append(p.relationships()).append("\n");
        sb.append("备注:     ").append(p.notes()).append("\n");
        return InteractionResult.ok(sb.toString());
    }

    private InteractionResult add(String name) {
        if (pm.exists(name)) return err("玩家已存在: " + name);
        PlayerProfile profile = PlayerProfile.createTemplate(name);
        pm.addPlayer(profile);
        return InteractionResult.ok("已创建玩家档案: " + name + "\n使用 /players set " + name + " <字段> <内容> 设置属性。\n文件: " + pm.getPlayersPath());
    }

    private InteractionResult set(String name, String field, String content) {
        // 字段别名映射
        String normalizedField = normalizeField(field);
        var update = pm.updatePlayerField(name, normalizedField, content);
        return InteractionResult.ok(
                (update.created() ? "已创建" : "已更新") + "玩家档案:\n"
                + "玩家: " + name + "\n"
                + "字段: " + normalizedField + " = " + content + "\n"
                + "文件: " + pm.getPlayersPath());
    }

    private InteractionResult note(String name, String note) {
        var update = pm.appendPlayerNote(name, note);
        return InteractionResult.ok(
                (update.created() ? "已创建" : "已更新") + "玩家备注:\n"
                + "玩家: " + name + "\n"
                + "备注: " + note + "\n"
                + "文件: " + pm.getPlayersPath());
    }

    private InteractionResult remove(String name) {
        Optional<PlayerProfile> opt = pm.getPlayer(name);
        if (opt.isEmpty()) return err("未找到玩家: " + name);

        // 打印将要删除的内容并提示确认
        StringBuilder sb = new StringBuilder();
        sb.append("将要删除玩家档案: ").append(name).append("\n\n");
        sb.append(opt.get().toMarkdown());
        sb.append("\n\n确认删除请执行: /players remove-confirm ").append(name);
        return InteractionResult.ok(sb.toString());
    }

    private InteractionResult removeConfirm(String name) {
        Optional<PlayerProfile> removed = pm.removePlayer(name);
        if (removed.isEmpty()) return err("未找到玩家: " + name);
        return InteractionResult.ok("已删除玩家档案: " + name + "\n文件: " + pm.getPlayersPath());
    }

    private InteractionResult importFile(String filePath) {
        Path src = Path.of(filePath);
        if (!Files.isRegularFile(src)) return err("文件不存在: " + filePath);
        try {
            String content = Files.readString(src, StandardCharsets.UTF_8);
            String current = pm.readRawPlayersMarkdown();
            if (!current.endsWith("\n")) current += "\n";
            pm.writeRawPlayersMarkdown(current + "\n" + content + "\n");
            return InteractionResult.ok("已导入: " + filePath + " → " + pm.getPlayersPath());
        } catch (IOException e) {
            return err("导入失败: " + e.getMessage());
        }
    }

    private InteractionResult exportFile(String filePath) {
        try {
            String content = pm.readRawPlayersMarkdown();
            Files.writeString(Path.of(filePath), content, StandardCharsets.UTF_8);
            return InteractionResult.ok("已导出: " + pm.getPlayersPath() + " → " + filePath);
        } catch (IOException e) {
            return err("导出失败: " + e.getMessage());
        }
    }

    private InteractionResult raw() {
        String content = pm.readRawPlayersMarkdown();
        if (content.length() > 3000) {
            return InteractionResult.ok(
                    content.substring(0, 3000) + "\n\n... (截断)\n完整文件: " + pm.getPlayersPath());
        }
        return InteractionResult.ok(content);
    }

    private InteractionResult template() {
        String tmpl = pm.renderTemplateForNewPlayer("玩家名");
        return InteractionResult.ok("新玩家模板:\n\n" + tmpl + "\n\n使用 /players add <玩家名> 创建。");
    }

    // ---- helpers ----

    private static InteractionResult err(String msg) { return InteractionResult.fail(msg); }

    private static String rest(String[] args, int start) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length));
    }

    /** 字段别名归一化。 */
    private static String normalizeField(String field) {
        return switch (field.toLowerCase()) {
            case "类型", "type" -> "type";
            case "阵营", "faction" -> "faction";
            case "身份", "identity" -> "identity";
            case "资源", "resources" -> "resources";
            case "公开目标", "publicgoal" -> "publicGoal";
            case "隐藏倾向", "hiddentendency" -> "hiddenTendency";
            case "当前状态", "currentstatus", "status" -> "currentStatus";
            case "关系", "relationships" -> "relationships";
            case "备注", "notes" -> "notes";
            default -> field;
        };
    }
}
