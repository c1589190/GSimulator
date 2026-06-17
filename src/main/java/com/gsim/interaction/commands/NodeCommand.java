package com.gsim.interaction.commands;

import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class NodeCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(NodeCommand.class);
    private final DataManager dm;

    public NodeCommand(DataManager dm) { this.dm = dm; }

    @Override public String name() { return "node"; }
    @Override public String description() { return "管理时间节点"; }
    @Override public String usage() { return "/node current|siblings|children|tree|switch <id>|show <id>"; }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        String full = String.join(" ", args).trim();
        String[] t = full.split("\\s+");
        if (t.length == 0) return fail("Usage: /node <current|siblings|children|tree|switch|show>");
        try {
            return switch (t[0]) {
                case "current" -> current();
                case "siblings" -> siblings();
                case "children" -> children();
                case "tree" -> tree();
                case "switch" -> switchTo(t);
                case "show" -> show(t);
                default -> fail("Unknown: " + t[0]);
            };
        } catch (Exception e) { return fail(e.getMessage()); }
    }

    private InteractionResult current() {
        DataDocument d = dm.readById(dm.getActiveBranch());
        if (d == null) return fail("Active branch not in memory");
        return ok("World: " + dm.getActiveWorld() + "\nBranch: " + d.id() +
                "\nName: " + d.name() + "\nParent: " + d.frontMatter().getOrDefault("parent", "none") +
                "\nTurn: " + d.frontMatter().getOrDefault("turn", "?") +
                "\nWorld Time: " + d.frontMatter().getOrDefault("world_time", "?") +
                "\nStatus: " + d.frontMatter().getOrDefault("status", "?"));
    }

    private InteractionResult siblings() {
        List<DataDocument> sibs = dm.getSiblingBranches(dm.getActiveBranch());
        StringBuilder sb = new StringBuilder("Siblings (parent same):\n");
        for (DataDocument d : sibs) {
            sb.append("  ").append(d.id().equals(dm.getActiveBranch()) ? "* " : "  ")
                    .append(d.id()).append(" — ").append(d.name())
                    .append(" [turn=").append(d.frontMatter().getOrDefault("turn","?")).append("]\n");
        }
        return ok(sb.toString().trim());
    }

    private InteractionResult children() {
        List<DataDocument> kids = dm.getChildBranches(dm.getActiveBranch());
        StringBuilder sb = new StringBuilder("Children:\n");
        for (DataDocument d : kids) sb.append("  ").append(d.id()).append(" — ").append(d.name()).append("\n");
        return ok(sb.toString().trim());
    }

    private InteractionResult tree() { return ok(dm.renderTimelineTreeWithActive().trim()); }

    private InteractionResult switchTo(String[] t) throws Exception {
        if (t.length < 2) return fail("Usage: /node switch <branchId>");
        dm.switchBranch(t[1]);
        return ok("Switched to: " + dm.getActiveBranch());
    }

    private InteractionResult show(String[] t) {
        if (t.length < 2) return fail("Usage: /node show <branchId>");
        DataDocument d = dm.readById(DataManager.normalizeBranchId(t[1]));
        return d != null ? ok(d.fullContent()) : fail("Not found: " + t[1]);
    }

    private static InteractionResult ok(String s) { return InteractionResult.ok(s); }
    private static InteractionResult fail(String s) { return InteractionResult.fail(s); }
}
