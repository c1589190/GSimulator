package com.gsim.interaction.commands;

import com.gsim.app.ApplicationContext;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;

/**
 * /where — 显示当前工作区位置信息。
 */
public class WhereCommand implements InteractionCommand {

    private final ApplicationContext ctx;

    public WhereCommand(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() { return "where"; }

    @Override
    public String description() {
        return "显示当前工作区位置：data root、active root、active branch、branch 文件路径等";
    }

    @Override
    public String usage() {
        return "/where";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        DataManager dm = ctx.getDataManager();
        StringBuilder sb = new StringBuilder();
        sb.append("========== 当前位置 ==========\n\n");

        // Data root
        sb.append("dataRoot: ").append(dm != null ? dm.getDataRoot() : "(not set)").append("\n");

        // Active root
        if (dm != null && !dm.needsRootBootstrap()) {
            sb.append("activeRoot: ").append(dm.getActiveRootId()).append("\n");
            sb.append("activeBranch: ").append(dm.getActiveBranch()).append("\n");

            // Branch file path
            String branchId = dm.getActiveBranch();
            if (branchId != null) {
                String fn = branchId.replace("branch.", "") + ".md";
                sb.append("branch file: data/worlds/").append(dm.getActiveRootId())
                        .append("/branches/").append(fn).append("\n");
            }

            // Root title (if readable) — 读取前 4000 chars，支持多种 Markdown 标题格式
            try {
                java.nio.file.Path worldFile = dm.worldFilePath();
                if (java.nio.file.Files.exists(worldFile)) {
                    String content = java.nio.file.Files.readString(worldFile);
                    String excerpt = content.length() > 4000 ? content.substring(0, 4000) : content;
                    String title = extractTitle(excerpt);
                    if (title != null) {
                        sb.append("root title: ").append(title).append("\n");
                    }
                }
            } catch (Exception ignored) {}

            // Knowledge DB path
            var dbPath = dm.getActiveKnowledgeDbPath();
            sb.append("knowledgeDb: ").append(dbPath != null ? dbPath : "(none)").append("\n");
        } else {
            sb.append("activeRoot: (none — use /root create or type a world description)\n");
        }

        // LLM config
        sb.append("\n--- LLM ---\n");
        var config = ctx.getConfig();
        sb.append("llmConfigured: ").append(config.isLlmConfigured()).append("\n");
        if (config.isLlmConfigured()) {
            sb.append("baseUrl: ").append(config.getLlmBaseUrl()).append("\n");
            sb.append("model: ").append(config.getLlmModel()).append("\n");
        }

        return InteractionResult.ok(sb.toString().trim());
    }

    /**
     * 从 world.md 文本中提取标题。支持格式：
     * - # Title（H1）
     * - ## 世界名称\nTitle（下一行）
     * - ## 世界名称\n\nTitle（下一行有空行）
     * - ## 世界名称：Title（中文冒号）
     * - ## 世界名称: Title（英文冒号）
     * - ## 世界名称 ： Title / ## 世界名称 : Title（空格+冒号+空格）
     * 返回 null 表示无法提取。
     */
    static String extractTitle(String excerpt) {
        if (excerpt == null || excerpt.isBlank()) return null;
        String[] lines = excerpt.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            // 格式: ## 世界名称（可能后接冒号+空格+标题）
            if (line.startsWith("## 世界名称")) {
                // 去掉前缀 "## 世界名称"，然后去掉可选的前导空格+冒号(中/英)+空格
                String afterHeading = line.replaceFirst("^## 世界名称\\s*[：:]?\\s*", "").trim();
                if (!afterHeading.isBlank()) {
                    return afterHeading;
                }
                // 同行无内容，检查下一行
                if (i + 1 < lines.length) {
                    String nextLine = lines[i + 1].trim();
                    if (!nextLine.isBlank() && !nextLine.startsWith("#")) {
                        return nextLine;
                    }
                    // 再跳过一个空行
                    if (nextLine.isBlank() && i + 2 < lines.length) {
                        String nextNext = lines[i + 2].trim();
                        if (!nextNext.isBlank() && !nextNext.startsWith("#")) {
                            return nextNext;
                        }
                    }
                }
                return null; // 找不到标题
            }
            // 格式: # Title（H1，不匹配 ##）
            if (line.startsWith("# ") && !line.startsWith("## ")) {
                String title = line.replaceFirst("^# ", "").trim();
                if (!title.isBlank() && !title.equals("世界观")) {
                    return title;
                }
            }
        }
        return null;
    }
}
