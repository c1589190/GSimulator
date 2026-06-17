package com.gsim.interaction.commands;

import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.data.DataSearchResult;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * /data 命令 — 管理 data/ Markdown 世界数据。
 *
 * 子命令：
 *   init, branch [create|switch], list [type], show <id>, search <kw>, reload, write
 */
public class DataCommand implements InteractionCommand {

    private static final Logger log = LoggerFactory.getLogger(DataCommand.class);

    private final DataManager dataManager;

    public DataCommand(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    public String name() { return "data"; }

    @Override
    public String description() {
        return "管理世界数据（初始化、分支、列表、搜索、查看）";
    }

    @Override
    public String usage() {
        return "/data init                           — 初始化默认世界数据\n" +
                "/data branch                         — 查看当前分支和所有分支\n" +
                "/data branch create <name> [from]    — 创建分支 (默认从 main)\n" +
                "/data branch switch <name>           — 切换分支\n" +
                "/data list [type]                    — 列出文档\n" +
                "/data show <id>                      — 查看文档\n" +
                "/data search <关键词>                — 搜索文档\n" +
                "/data reload                         — 重新加载当前分支\n" +
                "/data write <path>                   — 待实现";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args == null || args.length == 0 || args[0].isBlank()) {
            return InteractionResult.fail("Usage: /data <init|branch|list|show|search|reload>");
        }

        // 默认按空格分割参数
        String fullArgs = String.join(" ", args);
        String[] tokens = fullArgs.split("\\s+");

        String sub = tokens[0];
        try {
            return switch (sub) {
                case "init" -> doInit();
                case "branch" -> doBranch(tokens);
                case "list" -> doList(tokens);
                case "show" -> doShow(tokens);
                case "search" -> doSearch(tokens);
                case "reload" -> doReload();
                case "write" -> doWrite(tokens);
                default -> InteractionResult.fail("Unknown subcommand: " + sub +
                        ". Use: init, branch, list, show, search, reload");
            };
        } catch (Exception e) {
            log.error("/data {} failed: {}", sub, e.getMessage(), e);
            return InteractionResult.fail(e.getMessage());
        }
    }

    private InteractionResult doInit() throws Exception {
        dataManager.init();
        return InteractionResult.ok("Data system initialized in data/worlds/default/",
                "✅ 世界数据已初始化。\n" +
                "   目录: data/worlds/default/branches/main/\n" +
                "   分支: main\n" +
                "   包含: always/ (3), entities/ (2), turns/ (1)");
    }

    private InteractionResult doBranch(String[] tokens) throws Exception {
        if (tokens.length >= 3) {
            String action = tokens[1];
            return switch (action) {
                case "create" -> {
                    String name = tokens[2];
                    String from = tokens.length > 3 ? tokens[3] : "main";
                    dataManager.createBranch(name, from);
                    yield InteractionResult.ok("分支 " + name + " 已从 " + from + " 创建",
                            "✅ 分支 '" + name + "' 已从 '" + from + "' 创建。\n" +
                            "   使用 /data branch switch " + name + " 切换过去。");
                }
                case "switch" -> {
                    String name = tokens[2];
                    dataManager.switchBranch(name);
                    yield InteractionResult.ok("已切换到分支 " + name,
                            "✅ 已切换到分支 '" + name + "'。\n文档数: " + dataManager.listAll().size());
                }
                default -> InteractionResult.fail("Unknown branch action: " + action +
                        ". Use: create <name> [from], switch <name>");
            };
        }

        // 显示当前分支
        StringBuilder sb = new StringBuilder();
        sb.append("Active branch: ").append(dataManager.getCurrentBranch()).append("\n");
        sb.append("World: ").append(dataManager.getCurrentWorld()).append("\n\n");
        sb.append("Available branches:\n");
        for (String b : dataManager.listBranches()) {
            sb.append("  ");
            if (b.equals(dataManager.getCurrentBranch())) sb.append("* ");
            else sb.append("  ");
            sb.append(b).append("\n");
        }
        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult doList(String[] tokens) {
        String filterType = tokens.length > 1 ? tokens[1] : null;
        List<DataDocument> docs;
        if (filterType != null && !filterType.isBlank()) {
            docs = dataManager.listByType(filterType);
        } else {
            docs = List.copyOf(dataManager.listAll());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Documents");
        if (filterType != null) sb.append(" (type=").append(filterType).append(")");
        sb.append(": ").append(docs.size()).append("\n");
        sb.append("Branch: ").append(dataManager.getCurrentBranch()).append("\n\n");

        for (DataDocument doc : docs) {
            sb.append("  📄 ").append(doc.id());
            if (!doc.name().isBlank()) sb.append(" — ").append(doc.name());
            if (!doc.type().isBlank()) sb.append(" [").append(doc.type()).append("]");
            if (!doc.role().isBlank()) sb.append(" role=").append(doc.role());
            sb.append("\n     ").append(doc.rawPath()).append("\n");
        }
        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult doShow(String[] tokens) {
        if (tokens.length < 2) return InteractionResult.fail("Usage: /data show <id>");
        String id = tokens[1];
        DataDocument doc = dataManager.readById(id);
        if (doc == null) {
            return InteractionResult.fail("Document not found: " + id);
        }
        return InteractionResult.ok(doc.fullContent());
    }

    private InteractionResult doSearch(String[] tokens) {
        if (tokens.length < 2) return InteractionResult.fail("Usage: /data search <关键词>");
        StringBuilder kw = new StringBuilder();
        for (int i = 1; i < tokens.length; i++) {
            if (i > 1) kw.append(" ");
            kw.append(tokens[i]);
        }
        String keyword = kw.toString();

        List<DataSearchResult> results = dataManager.search(keyword, 10);

        StringBuilder sb = new StringBuilder();
        sb.append("=== 搜索结果 ===\n");
        sb.append("关键词: ").append(keyword).append("\n");
        sb.append("结果数: ").append(results.size()).append("\n\n");

        for (int i = 0; i < results.size(); i++) {
            DataSearchResult r = results.get(i);
            sb.append("[").append(i + 1).append("] ").append(r.id());
            if (!r.name().isBlank()) sb.append(" — ").append(r.name());
            if (!r.type().isBlank()) sb.append(" [").append(r.type()).append("]");
            if (!r.role().isBlank()) sb.append(" role=").append(r.role());
            sb.append("\n");
            sb.append("    path: ").append(r.path()).append("\n");
            sb.append("    score: ").append(String.format("%.1f", r.score())).append("\n");
            sb.append("    snippet: ").append(r.snippet()).append("\n\n");
        }
        if (results.isEmpty()) {
            sb.append("(无匹配结果)\n");
        }
        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult doReload() throws Exception {
        dataManager.reload();
        return InteractionResult.ok("已重新加载，文档数: " + dataManager.listAll().size(),
                "✅ 已重新加载分支 '" + dataManager.getCurrentBranch() + "'。\n" +
                "   文档数: " + dataManager.listAll().size());
    }

    private InteractionResult doWrite(String[] tokens) {
        return InteractionResult.fail("/data write 待后续阶段实现");
    }
}
