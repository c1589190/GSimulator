package com.gsim.ref;

import com.gsim.doc.DocStore;
import com.gsim.doc.Document;
import com.gsim.importing.ImportDocumentService;
import com.gsim.importing.ImportDocumentService.ImportDocumentReadResult;
import com.gsim.worldinfo.Element;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.WorldInfoBuilder;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 统一引用解析器 — 将 {@code @source:path} 格式的引用路由到对应后端。
 *
 * <p>支持的引用格式：
 * <ul>
 *   <li>{@code @import:<documentId>} — Import 文档</li>
 *   <li>{@code @world:<nodeId>:<cpId>:<key>} — World 元素（3 段）</li>
 *   <li>{@code @world:<cpId>:<key>} — World 元素（2 段，默认活跃节点）</li>
 *   <li>{@code @doc:<docId>} — Doc/Board 文档</li>
 * </ul>
 */
public final class RefResolver {

    private RefResolver() {}

    public record ResolvedRef(String source, String id, String title, String content) {}

    /**
     * 解析 @ 引用并返回统一结果。
     *
     * @param ref        完整的 @ 引用字符串
     * @param worldsDir   worlds 数据目录
     * @param activeWorldId 当前活跃 worldId
     * @param importDir   import 目录
     * @param docStore    DocStore 实例（可为 null，则 @doc: 不可用）
     */
    public static ResolvedRef resolve(String ref,
                                       Path worldsDir, String activeWorldId,
                                       Path importDir, DocStore docStore) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("ref must not be blank");
        }

        if (ref.startsWith("@import:")) {
            return resolveImport(ref.substring(8), importDir);
        } else if (ref.startsWith("@world:")) {
            return resolveWorld(ref.substring(7), worldsDir, activeWorldId);
        } else if (ref.startsWith("@doc:")) {
            return resolveDoc(ref.substring(5), docStore);
        } else {
            throw new IllegalArgumentException(
                    "Unknown ref prefix. Expected @import:, @world:, or @doc:. Got: " + ref);
        }
    }

    // ── @import:<documentId> ──

    private static ResolvedRef resolveImport(String documentId, Path importDir) {
        if (documentId.isBlank()) throw new IllegalArgumentException("@import: documentId must not be blank");
        ImportDocumentService service = new ImportDocumentService(importDir);
        try {
            ImportDocumentReadResult result = service.readDocument(documentId, 0, 30000, true);
            return new ResolvedRef("import", documentId,
                    result.displayName(), result.content());
        } catch (IOException e) {
            throw new IllegalArgumentException("Import document not found: " + documentId, e);
        }
    }

    // ── @world:<nodeId>:<cpId>:<key>  or  @world:<cpId>:<key> ──

    private static ResolvedRef resolveWorld(String path, Path worldsDir, String activeWorldId) {
        if (path.isBlank()) throw new IllegalArgumentException("@world: path must not be blank");

        if (activeWorldId == null || activeWorldId.isBlank()) {
            throw new IllegalStateException("No active world set");
        }

        String[] parts = path.split(":", 3);
        String nodeId, checkpointId, key;

        if (parts.length == 2) {
            nodeId = null; // will use active node
            checkpointId = parts[0].trim();
            key = parts[1].trim();
        } else if (parts.length == 3) {
            nodeId = parts[0].trim();
            checkpointId = parts[1].trim();
            key = parts[2].trim();
        } else {
            throw new IllegalArgumentException(
                    "@world: path must be <nodeId>:<cpId>:<key> or <cpId>:<key>. Got: " + path);
        }

        ActiveStateManager.ActiveState active = ActiveStateManager.load(worldsDir, activeWorldId);
        if (active == null) {
            throw new IllegalStateException("World has no active state: " + activeWorldId);
        }

        String resolveNodeId = nodeId != null ? nodeId : active.nodeId();
        WorldInformation wi = WorldInfoBuilder.build(worldsDir, activeWorldId, active.nodeId());
        if (wi == null) {
            throw new IllegalStateException("Cannot load world: " + activeWorldId);
        }

        NodeSnapshot node = wi.nodeById(resolveNodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node not found: " + resolveNodeId);
        }

        var cp = node.checkpoint(checkpointId);
        if (cp == null) {
            throw new IllegalArgumentException(
                    "Checkpoint not found: " + checkpointId + " in node " + resolveNodeId);
        }

        Element found = null;
        for (Element el : cp.elements()) {
            if (el.key().equals(key)) {
                found = el;
                break;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(
                    "Element not found: " + key + " in " + resolveNodeId + ":" + checkpointId);
        }

        String id = resolveNodeId + ":" + checkpointId + ":" + key;
        String title = key + " @" + resolveNodeId + " (turn " + node.turn() + ")";
        return new ResolvedRef("world", id, title, found.value());
    }

    // ── @doc:<docId> ──

    private static ResolvedRef resolveDoc(String docId, DocStore docStore) {
        if (docId.isBlank()) throw new IllegalArgumentException("@doc: docId must not be blank");
        if (docStore == null) throw new IllegalStateException("DocStore is not available");
        Document doc = docStore.get(docId);
        if (doc == null) {
            throw new IllegalArgumentException("Doc not found: " + docId);
        }
        String title = doc.title() + " (" + doc.id() + ")";
        return new ResolvedRef("doc", docId, title, doc.content());
    }
}
