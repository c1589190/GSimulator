package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.Element;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.NodeLoader;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * write_element — LLM writes a new element to a checkpoint in a node.
 */
public final class WriteElementTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;
    private final Path worldsDir;

    public WriteElementTool(Supplier<WorldInformation> worldInfo, Path worldsDir) {
        this.worldInfo = worldInfo;
        this.worldsDir = worldsDir;
    }

    @Override
    public String name() { return "write_element"; }

    @Override
    public String description() {
        return """
            Write an information element to a checkpoint.
            ref format: nodeId:checkpointId:key (e.g. 'n0002:characters:曹操')
            or checkpointId:key to default to the current active node.
            If the checkpoint does not exist, it will be auto-created (type='misc').
            By default (mode='replace'), if the key already exists it will be upserted.
            Use mode='append' to always add a new element.
            links should use the same nodeId:checkpointId:key format for cross-references.
            """;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String ref = call.param("ref");
        if (ref == null || ref.isBlank()) {
            return ToolResult.fail("write_element",
                "ref is required (format: nodeId:checkpointId:key or checkpointId:key)");
        }

        // Parse ref: nodeId:checkpointId:key  or  checkpointId:key
        WorldInformation wi = worldInfo.get();
        String[] parts = ref.split(":", 3);
        String nodeId, checkpointId, key;

        if (parts.length == 2) {
            nodeId = wi.activeNodeId();        // default to current node
            checkpointId = parts[0].trim();
            key = parts[1].trim();
        } else if (parts.length == 3) {
            nodeId = parts[0].trim();
            checkpointId = parts[1].trim();
            key = parts[2].trim();
        } else {
            return ToolResult.fail("write_element",
                "Invalid ref format: '" + ref + "'. Expected nodeId:checkpointId:key or checkpointId:key");
        }

        if (checkpointId.isEmpty() || key.isEmpty()) {
            return ToolResult.fail("write_element", "checkpointId and key must not be empty");
        }

        String type = call.param("type");
        String value = call.param("value");
        String tagsStr = call.param("tags");
        String linksStr = call.param("links");
        String mode = call.param("mode");

        if (value == null || value.isBlank()) {
            return ToolResult.fail("write_element", "value is required");
        }

        List<String> tags = tagsStr != null && !tagsStr.isBlank()
            ? Arrays.asList(tagsStr.split(","))
            : List.of();
        List<String> links = linksStr != null && !linksStr.isBlank()
            ? Arrays.asList(linksStr.split(","))
            : List.of();

        Element element = new Element(key, type != null ? type : "text", value, tags, links);

        boolean replaced;
        if ("append".equalsIgnoreCase(mode)) {
            wi.appendElement(nodeId, checkpointId, element);
            replaced = false;
        } else {
            replaced = wi.upsertElement(nodeId, checkpointId, element);
        }

        // persist
        Path nodeFile = NodeLoader.nodeFile(worldsDir, wi.worldId(), nodeId);
        NodeLoader.save(nodeFile, wi.nodeById(nodeId));

        String unifiedId = nodeId + ":" + checkpointId + ":" + key;
        String action = replaced ? "replaced" : "appended";
        return ToolResult.ok("write_element", List.of(
            new ToolResult.Item(key, unifiedId + " (" + action + ")", value, 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "ref", Map.of("type", "string", "description",
                    "Target element reference: nodeId:checkpointId:key (e.g. 'n0002:characters:曹操') " +
                    "or checkpointId:key to write to the current active node"),
                "type", Map.of("type", "string", "description",
                    "Element type: text, action, effect, narrative, character_state, etc. (default 'text')"),
                "value", Map.of("type", "string", "description", "Element content"),
                "tags", Map.of("type", "string", "description", "Comma-separated tags"),
                "links", Map.of("type", "string", "description",
                    "Comma-separated cross-references in nodeId:checkpointId:key format"),
                "mode", Map.of("type", "string", "description",
                    "'replace' (default, upsert by key) or 'append' (always add new element)")
            ),
            "required", List.of("ref", "value")
        );
    }
}
