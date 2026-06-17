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
 */
public class DataCommand implements InteractionCommand {

    private static final Logger log = LoggerFactory.getLogger(DataCommand.class);
    private final DataManager dm;

    public DataCommand(DataManager dm) { this.dm = dm; }

    @Override public String name() { return "data"; }
    @Override public String description() { return "管理世界数据（世界/分支/文档/搜索）"; }
    @Override public String usage() {
        return "/data status                  — 显示当前世界和分支\n" +
                "/data world                    — 列出所有世界\n" +
                "/data world create <name>      — 创建新世界\n" +
                "/data world switch <name>      — 切换世界\n" +
                "/data branch                   — 列出分支\n" +
                "/data branch create <n> [from] — 创建分支\n" +
                "/data branch switch <name>     — 切换分支\n" +
                "/data init                     — 显式初始化默认世界\n" +
                "/data list [type]              — 列出文档\n" +
                "/data show <id>                — 查看文档\n" +
                "/data search <关键词>           — 搜索文档\n" +
                "/data reload                   — 重新加载";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args == null || args.length == 0 || args[0].isBlank())
            return InteractionResult.fail("Usage: /data <status|world|branch|init|list|show|search|reload>");

        String full = String.join(" ", args);
        String[] tokens = full.split("\\s+");
        String sub = tokens[0];

        try {
            return switch (sub) {
                case "status" -> doStatus();
                case "world" -> doWorld(tokens);
                case "branch" -> doBranch(tokens);
                case "init" -> doInit();
                case "list" -> doList(tokens);
                case "show" -> doShow(tokens);
                case "search" -> doSearch(tokens);
                case "reload" -> doReload();
                default -> InteractionResult.fail("Unknown: " + sub);
            };
        } catch (Exception e) {
            log.error("/data {} failed: {}", sub, e.getMessage(), e);
            return InteractionResult.fail(e.getMessage());
        }
    }

    private InteractionResult doStatus() {
        return InteractionResult.ok(
                "world=" + dm.getCurrentWorld() + " branch=" + dm.getCurrentBranch() + " docs=" + dm.docCount(),
                "World: " + dm.getCurrentWorld() + "\nBranch: " + dm.getCurrentBranch() +
                "\nDocuments: " + dm.docCount() + "\nWorlds: " + dm.listWorlds() +
                "\nBranches: " + dm.listBranches());
    }

    private InteractionResult doWorld(String[] tokens) throws Exception {
        if (tokens.length >= 3) {
            String action = tokens[1];
            String name = tokens[2];
            return switch (action) {
                case "create" -> {
                    dm.createWorld(name);
                    yield InteractionResult.ok("World created: " + name,
                            "✅ 世界 '" + name + "' 已创建。\n使用 /data world switch " + name + " 切换。");
                }
                case "switch" -> {
                    dm.switchWorld(name);
                    yield InteractionResult.ok("Switched to world: " + name,
                            "✅ 已切换到世界 '" + name + "'。\n" +
                            "Branch: " + dm.getCurrentBranch() + "\nDocs: " + dm.docCount());
                }
                default -> InteractionResult.fail("Unknown: world " + action);
            };
        }
        StringBuilder sb = new StringBuilder("Worlds:\n");
        for (String w : dm.listWorlds()) {
            sb.append("  ").append(w.equals(dm.getCurrentWorld()) ? "* " : "  ").append(w).append("\n");
        }
        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult doBranch(String[] tokens) throws Exception {
        if (tokens.length >= 3) {
            String action = tokens[1];
            String name = tokens[2];
            return switch (action) {
                case "create" -> {
                    String from = tokens.length > 3 ? tokens[3] : "main";
                    dm.createBranch(name, from);
                    yield InteractionResult.ok("Branch created: " + name,
                            "✅ 分支 '" + name + "' 已从 '" + from + "' 创建。");
                }
                case "switch" -> {
                    dm.switchBranch(name);
                    yield InteractionResult.ok("Switched to branch: " + name,
                            "✅ 已切换到分支 '" + name + "'。\nDocs: " + dm.docCount());
                }
                default -> InteractionResult.fail("Unknown: branch " + action);
            };
        }
        StringBuilder sb = new StringBuilder();
        sb.append("World: ").append(dm.getCurrentWorld()).append("\n");
        sb.append("Active branch: ").append(dm.getCurrentBranch()).append("\n\nBranches:\n");
        for (String b : dm.listBranches()) {
            sb.append("  ").append(b.equals(dm.getCurrentBranch()) ? "* " : "  ").append(b).append("\n");
        }
        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult doInit() throws Exception {
        dm.init();
        return InteractionResult.ok("Initialized", "✅ 已初始化 world=default branch=main docs=" + dm.docCount());
    }

    private InteractionResult doList(String[] tokens) {
        String filter = tokens.length > 1 ? tokens[1] : null;
        List<DataDocument> docs = (filter != null && !filter.isBlank()) ? dm.listByType(filter) : List.copyOf(dm.listAll());
        StringBuilder sb = new StringBuilder();
        sb.append("World: ").append(dm.getCurrentWorld()).append(" | Branch: ").append(dm.getCurrentBranch()).append("\n");
        sb.append("Documents").append(filter != null ? " (type=" + filter + ")" : "").append(": ").append(docs.size()).append("\n\n");
        for (DataDocument d : docs) {
            sb.append("  ").append(d.id());
            if (!d.name().isBlank()) sb.append(" — ").append(d.name());
            if (!d.type().isBlank()) sb.append(" [").append(d.type()).append("]");
            if (!d.role().isBlank()) sb.append(" role=").append(d.role());
            sb.append("\n    ").append(d.rawPath()).append("\n");
        }
        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult doShow(String[] tokens) {
        if (tokens.length < 2) return InteractionResult.fail("Usage: /data show <id>");
        DataDocument doc = dm.readById(tokens[1]);
        return doc == null ? InteractionResult.fail("Not found: " + tokens[1])
                : InteractionResult.ok(doc.fullContent());
    }

    private InteractionResult doSearch(String[] tokens) {
        if (tokens.length < 2) return InteractionResult.fail("Usage: /data search <关键词>");
        StringBuilder kw = new StringBuilder();
        for (int i = 1; i < tokens.length; i++) { if (i > 1) kw.append(" "); kw.append(tokens[i]); }
        String keyword = kw.toString();
        List<DataSearchResult> results = dm.search(keyword, 10);
        StringBuilder sb = new StringBuilder();
        sb.append("World: ").append(dm.getCurrentWorld()).append(" | Branch: ").append(dm.getCurrentBranch()).append("\n");
        sb.append("关键词: ").append(keyword).append(" | 结果: ").append(results.size()).append("\n\n");
        for (int i = 0; i < results.size(); i++) {
            DataSearchResult r = results.get(i);
            sb.append("[").append(i + 1).append("] ").append(r.id());
            if (!r.name().isBlank()) sb.append(" — ").append(r.name());
            if (!r.type().isBlank()) sb.append(" [").append(r.type()).append("]");
            if (!r.role().isBlank()) sb.append(" role=").append(r.role());
            sb.append("\n    path: ").append(r.path());
            sb.append("  score: ").append(String.format("%.1f", r.score())).append("\n");
            sb.append("    snippet: ").append(r.snippet()).append("\n\n");
        }
        if (results.isEmpty()) sb.append("(无匹配)\n");
        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult doReload() throws Exception {
        dm.reload();
        return InteractionResult.ok("Reloaded", "✅ 已重新加载 world=" + dm.getCurrentWorld() +
                " branch=" + dm.getCurrentBranch() + " docs=" + dm.docCount());
    }
}
