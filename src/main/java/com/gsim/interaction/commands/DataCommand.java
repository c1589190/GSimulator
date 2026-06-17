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

public class DataCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(DataCommand.class);
    private final DataManager dm;
    public DataCommand(DataManager dm) { this.dm = dm; }

    @Override public String name() { return "data"; }
    @Override public String description() { return "管理世界数据"; }
    @Override public String usage() {
        return "/data status / world [create|switch] / branch [create|switch] / timeline / input [clear|text] / list / show / search / reload";
    }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        if (args == null || args.length == 0) return fail("Usage: /data <subcommand>");
        String full = String.join(" ", args).trim();
        if (full.isEmpty()) return fail("Usage: /data <subcommand>");
        String[] t = full.split("\\s+");
        try {
            return switch (t[0]) {
                case "status" -> status();
                case "world" -> world(t);
                case "branch" -> branch(t);
                case "timeline" -> timeline();
                case "input" -> input(t);
                case "list" -> list(t);
                case "show" -> show(t);
                case "search" -> search(t);
                case "reload" -> reload();
                default -> fail("Unknown: " + t[0]);
            };
        } catch (Exception e) { log.error("/data {}: {}", t[0], e.getMessage()); return fail(e.getMessage()); }
    }

    private InteractionResult status() {
        return ok("World: " + dm.getActiveWorld() + "\nBranch: " + dm.getActiveBranch() +
                "\nBranches: " + dm.listBranches().size() + "\nDocs: " + dm.docCount());
    }

    private InteractionResult world(String[] t) throws Exception {
        if (t.length >= 3) {
            String a = t[1], n = t[2];
            if ("create".equals(a)) { dm.createWorld(n); return ok("World created: " + n); }
            if ("switch".equals(a)) { dm.switchWorld(n); return ok("Switched to world: " + n + "\nBranch: " + dm.getActiveBranch()); }
        }
        StringBuilder sb = new StringBuilder("Worlds:\n");
        for (String w : dm.listWorlds()) sb.append("  ").append(w.equals(dm.getActiveWorld())?"* ":"  ").append(w).append("\n");
        return ok(sb.toString().trim());
    }

    private InteractionResult branch(String[] t) throws Exception {
        if (t.length >= 3) {
            String a = t[1];
            if ("create".equals(a)) {
                String id = t[2];
                String wt = t.length > 3 ? t[3] : "";
                DataDocument b = dm.createBranch(id, id.replace("branch.", "节点: "), wt);
                return ok("Branch created: " + b.id() + "\nParent: " + b.frontMatter().get("parent") + "\nTurn: " + b.frontMatter().get("turn"));
            }
            if ("switch".equals(a)) { dm.switchBranch(t[2]); return ok("Switched to: " + t[2]); }
            return fail("Unknown: branch " + a);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Active: ").append(dm.getActiveBranch()).append("\n\nBranches:\n");
        for (DataDocument b : dm.listBranches()) {
            sb.append("  ").append(b.id().equals(dm.getActiveBranch())?"* ":"  ").append(b.id())
                    .append(" — ").append(b.name()).append(" [turn=").append(b.frontMatter().getOrDefault("turn","?"))
                    .append(" parent=").append(b.frontMatter().getOrDefault("parent","none")).append("]\n");
        }
        return ok(sb.toString().trim());
    }

    private InteractionResult timeline() {
        StringBuilder sb = new StringBuilder("Timeline:\n");
        renderTree(dm.getTimelineTree(), sb, 0);
        return ok(sb.toString().trim());
    }
    private void renderTree(List<DataManager.TreeNode> nodes, StringBuilder sb, int depth) {
        for (DataManager.TreeNode n : nodes) {
            sb.append("  ".repeat(depth)).append(depth>0?"├─ ":"").append(n.id()).append(" — ").append(n.name()).append("\n");
            renderTree(n.children(), sb, depth+1);
        }
    }

    private InteractionResult input(String[] t) throws Exception {
        if (t.length < 2) return fail("Usage: /data input <text> or /data input clear");
        if ("clear".equals(t[1])) { dm.clearInput(); return ok("Input cleared."); }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < t.length; i++) { if (i>1) sb.append(" "); sb.append(t[i]); }
        dm.appendInput(sb.toString());
        return ok("Input appended.");
    }

    private InteractionResult list(String[] t) {
        String filter = t.length > 1 ? t[1] : null;
        List<DataDocument> docs = (filter != null && !filter.isBlank()) ? dm.listByType(filter) : dm.listAll();
        StringBuilder sb = new StringBuilder("World: " + dm.getActiveWorld() + " | Branch: " + dm.getActiveBranch() + "\n");
        sb.append("Docs").append(filter!=null?"(type="+filter+")":"").append(": ").append(docs.size()).append("\n\n");
        for (DataDocument d : docs) sb.append("  ").append(d.id()).append(" — ").append(d.name())
                .append(" [").append(d.type()).append("]\n    ").append(d.rawPath()).append("\n");
        return ok(sb.toString().trim());
    }

    private InteractionResult show(String[] t) {
        if (t.length < 2) return fail("Usage: /data show <id>");
        DataDocument d = dm.readById(t[1]);
        return d != null ? ok(d.fullContent()) : fail("Not found: " + t[1]);
    }

    private InteractionResult search(String[] t) {
        if (t.length < 2) return fail("Usage: /data search <kw>");
        StringBuilder kw = new StringBuilder();
        for (int i=1;i<t.length;i++){if(i>1)kw.append(" ");kw.append(t[i]);}
        List<DataSearchResult> r = dm.search(kw.toString(), 10);
        StringBuilder sb = new StringBuilder("World: "+dm.getActiveWorld()+" | Branch: "+dm.getActiveBranch()+"\n");
        sb.append("Search: ").append(kw).append(" | Results: ").append(r.size()).append("\n\n");
        for (int i=0;i<r.size();i++){ DataSearchResult rs=r.get(i);
            sb.append("[").append(i+1).append("] ").append(rs.id()).append(" — ").append(rs.name())
                    .append(" [").append(rs.type()).append("] score=").append(String.format("%.1f",rs.score())).append("\n");
            sb.append("    path: ").append(rs.path()).append("\n    snippet: ").append(rs.snippet()).append("\n\n");
        }
        if(r.isEmpty())sb.append("(no results)\n");
        return ok(sb.toString().trim());
    }

    private InteractionResult reload() throws Exception { dm.reload(); return ok("Reloaded. Docs: "+dm.docCount()); }

    private static InteractionResult ok(String s) { return InteractionResult.ok(s); }
    private static InteractionResult fail(String s) { return InteractionResult.fail(s); }
}
