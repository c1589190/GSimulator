package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.skill.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class SkillCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(SkillCommand.class);
    private final SkillManager sm;
    public SkillCommand(SkillManager sm) { this.sm = sm; }

    @Override public String name() { return "skill"; }
    @Override public String description() { return "管理 Agent 推演技能"; }
    @Override public String usage() { return "/skill list | show <id> | search <kw> | context | summarize"; }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        if (args == null || args.length == 0) return fail("Usage: /skill <list|show|search|context|summarize>");
        String full = String.join(" ", args).trim();
        String[] t = full.split("\\s+");
        try {
            return switch (t[0]) {
                case "list" -> list();
                case "show" -> show(t);
                case "search" -> search(t);
                case "context" -> context();
                case "summarize" -> summarize();
                default -> fail("Unknown: " + t[0]);
            };
        } catch (Exception e) { log.error("/skill {}: {}", t[0], e.getMessage()); return fail(e.getMessage()); }
    }

    private InteractionResult list() {
        StringBuilder sb = new StringBuilder("Skills:\n");
        for (var s : sm.listSkills()) sb.append("  ").append(s.id()).append(" — ").append(s.name()).append("\n");
        return ok(sb.toString().trim());
    }

    private InteractionResult show(String[] t) {
        if (t.length < 2) return fail("Usage: /skill show <id>");
        var s = sm.readSkill(t[1]);
        if (s == null) return fail("Not found: " + t[1]);
        StringBuilder sb = new StringBuilder();
        for (var e : s.fm().entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        sb.append("-------------------\n\n").append(s.body());
        return ok(sb.toString());
    }

    private InteractionResult search(String[] t) {
        if (t.length < 2) return fail("Usage: /skill search <kw>");
        StringBuilder kw = new StringBuilder();
        for (int i=1;i<t.length;i++){if(i>1)kw.append(" ");kw.append(t[i]);}
        List<SkillManager.SkillDoc> r = sm.searchSkills(kw.toString(), 10);
        StringBuilder sb = new StringBuilder("Search: ").append(kw).append(" | Results: ").append(r.size()).append("\n\n");
        for (int i=0;i<r.size();i++){ var s=r.get(i);
            sb.append("[").append(i+1).append("] ").append(s.id()).append(" — ").append(s.name()).append("\n");
            sb.append("    snippet: ").append(s.body().length()>200?s.body().substring(0,200)+"...":s.body()).append("\n\n");
        }
        if(r.isEmpty())sb.append("(no results)\n");
        return ok(sb.toString().trim());
    }

    private InteractionResult context() { return ok(sm.getSkillContext()); }

    private InteractionResult summarize() throws Exception {
        String c = sm.summarizeExperienceToSkill();
        return ok("Generated skill summary.\n\n" + (c.length()>500?c.substring(0,500)+"...":c));
    }

    private static InteractionResult ok(String s) { return InteractionResult.ok(s); }
    private static InteractionResult fail(String s) { return InteractionResult.fail(s); }
}
