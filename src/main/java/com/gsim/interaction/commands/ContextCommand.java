package com.gsim.interaction.commands;

import com.gsim.context.BranchContextRenderer;
import com.gsim.context.RenderedContext;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(ContextCommand.class);
    private final BranchContextRenderer renderer;

    public ContextCommand(BranchContextRenderer renderer) { this.renderer = renderer; }

    @Override public String name() { return "context"; }
    @Override public String description() { return "渲染当前分支的 LLM 上下文"; }
    @Override public String usage() { return "/context status | render | save"; }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        if (args == null || args.length == 0) return fail("Usage: /context <status|render|save>");
        String full = String.join(" ", args).trim();
        String[] t = full.split("\\s+");
        try {
            return switch (t[0]) {
                case "status" -> status();
                case "render" -> render();
                case "save" -> save();
                default -> fail("Unknown: " + t[0]);
            };
        } catch (Exception e) { log.error("/context {}: {}", t[0], e.getMessage()); return fail(e.getMessage()); }
    }

    private InteractionResult status() {
        RenderedContext ctx = renderer.render();
        return ok("World: " + ctx.activeWorld() + "\nBranch: " + ctx.activeBranch() +
                "\nChain: " + ctx.chainLength() + "\nSystem.md: " + (ctx.systemPromptExists() ? "yes" : "no") +
                "\nMessages: " + ctx.messages().size());
    }

    private InteractionResult render() {
        String md = renderer.renderAsMarkdown();
        return ok(md.length() > 3000 ? md.substring(0, 3000) + "\n\n... (truncated, use /context save for full)" : md);
    }

    private InteractionResult save() throws Exception {
        renderer.saveRendered();
        return ok("Saved to data/worlds/" + renderer.render().activeWorld() + "/rendered-context.md");
    }

    private static InteractionResult ok(String s) { return InteractionResult.ok(s); }
    private static InteractionResult fail(String s) { return InteractionResult.fail(s); }
}
