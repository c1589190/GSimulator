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
import java.nio.file.Files;
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
 *   <li>{@code @cache:<id>} — 缓存文本（来自 write_element 等的大文本输出）</li>
 * </ul>
 */
public final class RefResolver {

    private RefResolver() {}

    /**
     * 引用解析结果记录。
     *
     * @param source  引用来源类型（import / world / doc / cache）
     * @param id      引用 ID
     * @param title   可读标题
     * @param content 引用内容文本
     */
    public record ResolvedRef(String source, String id, String title, String content) {}

    /**
     * 解析 @ 引用并返回统一结果。
     * <p>
     * 支持的格式：@import:、@world:、@doc:、@cache:。
     *
     * @param ref           引用字符串（如 "@import:doc123"）
     * @param worldsDir     世界目录根路径
     * @param activeWorldId 当前活跃世界 ID
     * @param importDir     导入文档目录
     * @param docStore      文档存储（用于 @doc: 和 route_to_doc 解析）
     * @param cacheDir      缓存目录（用于 @cache: 解析）
     * @return 解析后的 ResolvedRef 结果
     * @throws IllegalArgumentException 引用格式无法识别或资源不存在时抛出
     * @throws IllegalStateException    必要的上下文（如 activeWorldId）未配置时抛出
     */
    public static ResolvedRef resolve(
            String ref, Path worldsDir, String activeWorldId, Path importDir, DocStore docStore, Path cacheDir) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("ref must not be blank");
        }

        if (ref.startsWith("@import:")) {
            return resolveImport(ref.substring(8), importDir);
        } else if (ref.startsWith("@world:")) {
            return resolveWorld(ref.substring(7), worldsDir, activeWorldId, docStore);
        } else if (ref.startsWith("@doc:")) {
            return resolveDoc(ref.substring(5), docStore);
        } else if (ref.startsWith("@cache:")) {
            return resolveCache(ref.substring(7), cacheDir);
        } else {
            throw new IllegalArgumentException(
                    "Unknown ref prefix. Expected @import:, @world:, @doc:, or @cache:. Got: " + ref);
        }
    }

    // ── @import:<documentId> ──

    private static ResolvedRef resolveImport(String documentId, Path importDir) {
        if (documentId.isBlank()) throw new IllegalArgumentException("@import: documentId must not be blank");
        ImportDocumentService service = new ImportDocumentService(importDir);
        try {
            ImportDocumentReadResult result = service.readDocument(documentId, 0, 30000, true);
            return new ResolvedRef("import", documentId, result.displayName(), result.content());
        } catch (IOException e) {
            throw new IllegalArgumentException("Import document not found: " + documentId, e);
        }
    }

    // ── @world:<nodeId>:<cpId>:<key>  or  @world:<cpId>:<key> ──

    private static ResolvedRef resolveWorld(String path, Path worldsDir, String activeWorldId, DocStore docStore) {
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

        String resolveNodeId = nodeId != null ? nodeId : "n0000";
        WorldInformation wi = WorldInfoBuilder.build(worldsDir, activeWorldId, "n0000");
        if (wi == null) {
            throw new IllegalStateException("Cannot load world: " + activeWorldId);
        }

        NodeSnapshot node = wi.nodeById(resolveNodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node not found: " + resolveNodeId);
        }

        var cp = node.checkpoint(checkpointId);
        if (cp == null) {
            throw new IllegalArgumentException("Checkpoint not found: " + checkpointId + " in node " + resolveNodeId);
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

        // route_to_doc：自动解析 @doc:xxx → Doc 全文
        String content = found.value();
        if ("route_to_doc".equals(found.type()) && content != null && content.startsWith("@doc:") && docStore != null) {
            String docId = content.substring(5).trim();
            if (!docId.isEmpty()) {
                Document doc = docStore.get(docId);
                if (doc != null) {
                    content = doc.content();
                    title = doc.title() + " (via " + id + ")";
                }
            }
        }

        return new ResolvedRef("world", id, title, content);
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

    // ── @cache:<id> ──

    private static ResolvedRef resolveCache(String cacheId, Path cacheDir) {
        if (cacheId.isBlank()) throw new IllegalArgumentException("@cache: id must not be blank");
        if (cacheDir == null) throw new IllegalStateException("Cache directory is not available");
        Path file = cacheDir.resolve(cacheId + ".txt");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Cache entry not found: " + cacheId);
        }
        try {
            String content = Files.readString(file);
            return new ResolvedRef("cache", cacheId, "@cache:" + cacheId, content);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read cache: " + cacheId, e);
        }
    }
}
