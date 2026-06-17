package com.gsim.interaction.commands;

import com.gsim.experience.ExperienceManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ExpCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(ExpCommand.class);
    private final ExperienceManager em;
    public ExpCommand(ExperienceManager em) { this.em = em; }

    @Override public String name() { return "exp"; }
    @Override public String description() { return "管理交互经验"; }
    @Override public String usage() { return "/exp list | show <id> | search <kw> | add <title> <content>"; }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        if (args == null || args.length == 0) return fail("Usage: /exp <list|show|search|add>");
        String full = String.join(" ", args).trim();
        String[] t = full.split("\\s+");
        try {
            return switch (t[0]) {
                case "list" -> list();
                case "show" -> show(t);
                case "search" -> search(t);
                case "add" -> add(t);
                default -> fail("Unknown: " + t[0]);
            };
        } catch (Exception e) { log.error("/exp {}: {}", t[0], e.getMessage()); return fail(e.getMessage()); }
    }

    private InteractionResult list() {
        StringBuilder sb = new StringBuilder("Experiences:\n");
        for (var e : em.listExperiences()) sb.append("  ").append(e.id()).append(" — ").append(e.name()).append("\n");
        return ok(sb.toString().trim());
    }

    private InteractionResult show(String[] t) {
        if (t.length < 2) return fail("Usage: /exp show <id>");
        var e = em.readExperience(t[1]);
        if (e == null) return fail("Not found: " + t[1]);
        StringBuilder sb = new StringBuilder();
        for (var entry : e.fm().entrySet()) sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        sb.append("-------------------\n\n").append(e.body());
        return ok(sb.toString());
    }

    private InteractionResult search(String[] t) {
        if (t.length < 2) return fail("Usage: /exp search <kw>");
        StringBuilder kw = new StringBuilder();
        for (int i=1;i<t.length;i++){if(i>1)kw.append(" ");kw.append(t[i]);}
        List<ExperienceManager.ExpDoc> r = em.searchExperiences(kw.toString(), 10);
        StringBuilder sb = new StringBuilder("Search: ").append(kw).append(" | Results: ").append(r.size()).append("\n\n");
        for (int i=0;i<r.size();i++){ var e=r.get(i);
            sb.append("[").append(i+1).append("] ").append(e.id()).append(" — ").append(e.name()).append("\n");
            sb.append("    snippet: ").append(e.body().length()>200?e.body().substring(0,200)+"...":e.body()).append("\n\n");
        }
        if(r.isEmpty())sb.append("(no results)\n");
        return ok(sb.toString().trim());
    }

    private InteractionResult add(String[] t) throws Exception {
        if (t.length < 3) return fail("Usage: /exp add <title> <content>");
        String title = t[1];
        StringBuilder content = new StringBuilder();
        for (int i=2;i<t.length;i++){if(i>2)content.append(" ");content.append(t[i]);}
        var e = em.writeExperience(title, content.toString());
        return ok("Experience added: " + e.id() + " — " + e.name());
    }

    private static InteractionResult ok(String s) { return InteractionResult.ok(s); }
    private static InteractionResult fail(String s) { return InteractionResult.fail(s); }
}
