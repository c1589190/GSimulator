package com.gsim.context;

import com.gsim.worldinfo.ElementRef;
import com.gsim.worldinfo.WorldInformation;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders agent system prompts from FreeMarker templates in the prompts/ directory.
 */
public final class ContextRenderer {

    private final Configuration fm;
    private final Path promptsDir;

    public ContextRenderer(Path promptsDir) {
        this.promptsDir = promptsDir;
        this.fm = new Configuration(Configuration.VERSION_2_3_34);
        try {
            this.fm.setDirectoryForTemplateLoading(promptsDir.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Cannot set template directory: " + promptsDir, e);
        }
        this.fm.setDefaultEncoding("UTF-8");
    }

    /**
     * Render a system prompt for the given agent with world context injected.
     */
    public String renderSystemPrompt(String agentName, WorldInformation wi) {
        String templateName = agentName + "_system.md";
        Map<String, Object> data = buildDataModel(wi);
        return render(templateName, data);
    }

    /**
     * Render the compression prompt.
     */
    public String renderCompressPrompt(String agentName, String conversationText) {
        String templateName = agentName + "_compress.md";
        Map<String, Object> data = Map.of("conversation", conversationText);
        return render(templateName, data);
    }

    // -- private --

    private String render(String templateName, Map<String, Object> data) {
        try {
            Template t = fm.getTemplate(templateName);
            StringWriter sw = new StringWriter();
            t.process(data, sw);
            return sw.toString();
        } catch (IOException e) {
            // fallback: read raw file without processing
            Path file = promptsDir.resolve(templateName);
            if (Files.exists(file)) {
                try { return Files.readString(file); } catch (IOException ex) { /* ignore */ }
            }
            throw new RuntimeException("Cannot render template: " + templateName, e);
        } catch (TemplateException e) {
            throw new RuntimeException("Template error: " + templateName, e);
        }
    }

    private Map<String, Object> buildDataModel(WorldInformation wi) {
        Map<String, Object> data = new HashMap<>();
        data.put("worldId", wi.worldId());
        data.put("rootNodeId", wi.rootNodeId());
        data.put("activeNodeId", wi.activeNodeId());
        data.put("activeTurn", wi.activeNode().turn());
        data.put("worldTime", wi.activeNode().worldTime());
        data.put("chainLength", wi.branchChain().size());
        data.put("checkpointIds", wi.allCheckpointIds());
        data.put("task", ""); // default empty, sub-agent templates use ${task!""}

        // recent 3 narratives
        List<ElementRef> narratives = wi.checkpointHistory("narrative");
        int start = Math.max(0, narratives.size() - 3);
        data.put("recentNarratives", narratives.subList(start, narratives.size()).stream()
            .map(ref -> Map.of("turn", ref.turn(), "worldTime", ref.worldTime(), "text", ref.element().value()))
            .toList());

        return data;
    }
}
