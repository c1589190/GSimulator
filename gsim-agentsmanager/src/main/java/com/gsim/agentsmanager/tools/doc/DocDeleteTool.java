package com.gsim.agentsmanager.tools.doc;

import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.doc.Document;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * gsim_doc_delete -- Delete a document by docId from the unified DocStore.
 *
 * <p>Docs are identified by their docId (e.g. "test_doc_character").
 * The tool looks up the document in DocStore and removes it, including
 * the on-disk file and the in-memory cache entry.
 */
public final class DocDeleteTool implements AgentTool {

    private final DocStore store;

    public DocDeleteTool(DocStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "doc_delete";
    }

    @Override
    public String description() {
        return """
            Delete a document by docId.
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

        Document doc = store.get(docId);
        if (doc == null) {
            return ToolResult.fail(name(), "Document not found: " + docId);
        }

        try {
            store.delete(docId);
            return ToolResult.ok(
                    name(),
                    List.of(new ToolResult.Item(
                            docId,
                            docId,
                            "Document deleted: " + docId + " (type="
                                    + doc.type().key() + ")",
                            1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "Failed to delete document: " + e.getMessage());
        }
    }

    @Override
    public Permission permission() {
        return Permission.SYSTEM;
    }
}
