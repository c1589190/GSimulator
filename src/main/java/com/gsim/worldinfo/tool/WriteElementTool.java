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
        return "Write a new information element to a checkpoint. " +
               "Used to record simulation results, narrative text, state changes, etc.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String nodeId = call.param("nodeId");
        String checkpointId = call.param("checkpointId");
        String key = call.param("key");
        String type = call.param("type");
        String value = call.param("value");
        String tagsStr = call.param("tags");
        String linksStr = call.param("links");

        if (nodeId == null || checkpointId == null || key == null || value == null) {
            return ToolResult.fail("write_element",
                "nodeId, checkpointId, key, value are required");
        }

        List<String> tags = tagsStr != null && !tagsStr.isBlank()
            ? Arrays.asList(tagsStr.split(","))
            : List.of();
        List<String> links = linksStr != null && !linksStr.isBlank()
            ? Arrays.asList(linksStr.split(","))
            : List.of();

        Element element = new Element(key, type != null ? type : "text", value, tags, links);

        WorldInformation wi = worldInfo.get();
        wi.appendElement(nodeId, checkpointId, element);

        // persist
        Path nodeFile = NodeLoader.nodeFile(worldsDir, wi.worldId(), nodeId);
        NodeLoader.save(nodeFile, wi.nodeById(nodeId));

        return ToolResult.ok("write_element", List.of(
            new ToolResult.Item(key, checkpointId + "@" + nodeId, value, 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "nodeId", Map.of("type", "string", "description", "Target node ID"),
                "checkpointId", Map.of("type", "string", "description", "Target checkpoint ID"),
                "key", Map.of("type", "string", "description", "Element key (unique within checkpoint)"),
                "type", Map.of("type", "string", "description", "Element type: text, action, effect, narrative, etc."),
                "value", Map.of("type", "string", "description", "Element content"),
                "tags", Map.of("type", "string", "description", "Comma-separated tags"),
                "links", Map.of("type", "string", "description", "Comma-separated link targets")
            ),
            "required", List.of("nodeId", "checkpointId", "key", "value")
        );
    }
}
