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

            // Root title (if readable)
            try {
                java.nio.file.Path worldFile = dm.worldFilePath();
                if (java.nio.file.Files.exists(worldFile)) {
                    String content = java.nio.file.Files.readString(worldFile);
                    // Extract title from first # heading or ## 世界名称
                    for (String line : content.split("\n")) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("## 世界名称")) {
                            String title = trimmed.replace("## 世界名称", "").trim();
                            if (!title.isBlank()) {
                                sb.append("root title: ").append(title).append("\n");
                            }
                            break;
                        }
                        if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                            String title = trimmed.replace("# ", "").trim();
                            if (!title.isBlank() && !title.equals("世界观")) {
                                sb.append("root title: ").append(title).append("\n");
                            }
                            break;
                        }
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
}
