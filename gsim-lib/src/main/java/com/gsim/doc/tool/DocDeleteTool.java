package com.gsim.doc.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * gsim_delete_doc -- Delete an import document by docId.
 *
 * <p>Directly removes the file from the import documents directory.
 * The docId can be a relative path or just a filename; if not found
 * directly, a walk search is performed as a fallback.
 */
public final class DocDeleteTool implements AgentTool {

    private final Path importDir;

    public DocDeleteTool(Path importDir) {
        this.importDir = importDir;
    }

    @Override
    public String name() {
        return "gsim_delete_doc";
    }

    @Override
    public String description() {
        return """
            Delete an import document by docId.
            Parameters: docId (required) -- document ID to delete.
            """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("docId", Map.of("type", "string", "description", "Document ID to delete")),
                "required", List.of("docId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String docId = call.param("docId", "").trim();
        if (docId.isEmpty()) {
            return ToolResult.fail(name(), "docId is required");
        }

        // Resolve document path
        Path docPath = importDir.resolve(docId);
        if (!Files.exists(docPath)) {
            // Fallback: walk the import dir looking for a matching filename
            try (var files = Files.walk(importDir)) {
                var found = files.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().equals(docId))
                        .findFirst();
                if (found.isPresent()) {
                    docPath = found.get();
                }
            } catch (IOException ignored) {
            }
        }

        if (!Files.exists(docPath)) {
            return ToolResult.fail(name(), "Document not found: " + docId);
        }

        try {
            Files.delete(docPath);
            return ToolResult.ok(name(), List.of(new ToolResult.Item(docId, docId, "Document deleted: " + docId, 1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "Failed to delete document: " + e.getMessage());
        }
    }
}
