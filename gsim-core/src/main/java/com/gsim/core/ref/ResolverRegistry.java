package com.gsim.core.ref;

import com.gsim.docslib.doc.Document;
import com.gsim.core.importing.ImportDocumentService;
import com.gsim.core.importing.ImportDocumentService.ImportDocumentReadResult;
import com.gsim.core.ref.RefResolver.ResolvedRef;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.ActiveStateManager;
import com.gsim.core.worldinfo.loader.WorldManager;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 可插拔引用解析注册中心 — 将 {@code @world:/@doc:/@cache:/@import:/gsimap:/裸引用} 统一分发到对应
 * {@link Resolver}。
 *
 * <p>内置 resolver（{@link #createWithBuiltins()}）覆盖 {@code @world}（三段式与两段式）、
 * {@code @doc}、{@code @cache}、{@code @import} 与裸引用（{@code nodeId:cpId:key} /
 * {@code cpId:key}，按 @world 语义解析）。{@code gsimap:} 前缀由应用层（gsim-agent）提供
 * {@code GsimapResolver}（依赖 MapService），装配时注册进本注册中心。
 *
 * <p><b>活跃节点修复：</b> {@code @world:cpId:key} 两段式解析到
 * {@link WorldManager#activeNodeId(String)}（turn 最大的节点），而非历史硬编码 {@code "n0000"}。
 *
 * <p>注册中心无状态、线程安全（只读的 resolver 列表）；每次解析的上下文经
 * {@link ResolverContext} 按次传入。
 */
public final class ResolverRegistry {

    private static final String GSIMAP_PREFIX = "gsimap";

    private final List<Resolver> resolvers = new ArrayList<>();

    private ResolverRegistry() {}

    /**
     * 创建注册中心并注册全部内置 resolver（@world/@doc/@cache/@import/裸引用）。
     */
    public static ResolverRegistry createWithBuiltins() {
        ResolverRegistry registry = new ResolverRegistry();
        registry.register(new WorldResolver());
        registry.register(new DocResolver());
        registry.register(new CacheResolver());
        registry.register(new ImportResolver());
        registry.register(new BareRefResolver());
        return registry;
    }

    /**
     * 注册一个解析器。裸引用解析器（prefix 为空串）作为兜底，最后生效。
     */
    public void register(Resolver resolver) {
        resolvers.add(java.util.Objects.requireNonNull(resolver, "resolver"));
    }

    /**
     * 解析引用并返回统一结果。
     *
     * @param ref 引用字符串（如 "@world:worldview:flag"、"gsimap:region:迷雾森林"、"n0001:characters:曹操"）
     * @param ctx 本次解析上下文
     * @return 解析后的 ResolvedRef 结果
     * @throws IllegalArgumentException 引用格式无法识别或资源不存在时抛出
     * @throws IllegalStateException    必要的上下文（如活跃 worldId）未配置时抛出
     */
    public ResolvedRef resolve(String ref, ResolverContext ctx) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("ref must not be blank");
        }
        Resolver bare = null;
        for (Resolver resolver : resolvers) {
            String prefix = resolver.prefix();
            if (prefix.isEmpty()) {
                bare = resolver; // 裸引用兜底：优先精确前缀匹配
                continue;
            }
            if (ref.startsWith(prefix + ":")) {
                return resolver.resolve(ref.substring(prefix.length() + 1), ctx);
            }
        }
        if (ref.startsWith(GSIMAP_PREFIX + ":") && !isRegistered(GSIMAP_PREFIX)) {
            throw new IllegalArgumentException(
                    "gsimap: prefix requires a registered GsimapResolver (missing in this registry)");
        }
        if (bare != null && !ref.startsWith("@")) {
            return bare.resolve(ref, ctx);
        }
        throw new IllegalArgumentException(
                "Unknown ref prefix. Expected @world:, @doc:, @cache:, @import:, or gsimap:. Got: " + ref);
    }

    private boolean isRegistered(String prefix) {
        for (Resolver resolver : resolvers) {
            if (prefix.equals(resolver.prefix())) return true;
        }
        return false;
    }

    // ── 内置 resolver ──

    /** @world: 三段式 / 两段式（两段式解析到活跃节点）。 */
    private static final class WorldResolver implements Resolver {

        @Override
        public String prefix() {
            return "@world";
        }

        @Override
        public ResolvedRef resolve(String path, ResolverContext ctx) {
            return resolveWorldPath(path, ctx);
        }
    }

    /** 裸引用兜底：nodeId:cpId:key / cpId:key，按 @world 语义解析。 */
    private static final class BareRefResolver implements Resolver {

        @Override
        public String prefix() {
            return "";
        }

        @Override
        public ResolvedRef resolve(String path, ResolverContext ctx) {
            return resolveWorldPath(path, ctx);
        }
    }

    /**
     * @world 语义的路径解析（@world: 与裸引用共用）：三段式走显式节点，两段式走活跃节点。
     */
    private static ResolvedRef resolveWorldPath(String path, ResolverContext ctx) {
        if (path.isBlank()) throw new IllegalArgumentException("@world: path must not be blank");

        if (ctx.activeWorldId() == null || ctx.activeWorldId().isBlank()) {
            throw new IllegalStateException("No active world set");
        }

        String[] parts = path.split(":", 3);
        String nodeId, checkpointId, key;

        if (parts.length == 2) {
            nodeId = null; // 两段式 → 活跃节点
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

        ActiveStateManager.ActiveState active = ActiveStateManager.load(ctx.worldsDir(), ctx.activeWorldId());
        if (active == null) {
            throw new IllegalStateException("World has no active state: " + ctx.activeWorldId());
        }

        WorldManager worldManager = ctx.worldManager().get();
        // 修复：两段式解析到活跃节点（turn 最大），而非硬编码 "n0000"
        String resolveNodeId = nodeId != null ? nodeId : worldManager.activeNodeId(ctx.activeWorldId());
        if (resolveNodeId == null) {
            throw new IllegalStateException("Cannot determine active node for world: " + ctx.activeWorldId());
        }

        WorldInformation wi = worldManager.loadWorld(ctx.activeWorldId());
        if (wi == null) {
            throw new IllegalStateException("Cannot load world: " + ctx.activeWorldId());
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
        if ("route_to_doc".equals(found.type())
                && content != null
                && content.startsWith("@doc:")
                && ctx.docStore() != null) {
            String docId = content.substring(5).trim();
            if (!docId.isEmpty()) {
                Document doc = ctx.docStore().get(docId);
                if (doc != null) {
                    content = doc.content();
                    title = doc.title() + " (via " + id + ")";
                }
            }
        }

        return new ResolvedRef("world", id, title, content);
    }

    /** @doc:<docId>。 */
    private static final class DocResolver implements Resolver {

        @Override
        public String prefix() {
            return "@doc";
        }

        @Override
        public ResolvedRef resolve(String docId, ResolverContext ctx) {
            if (docId.isBlank()) throw new IllegalArgumentException("@doc: docId must not be blank");
            if (ctx.docStore() == null) throw new IllegalStateException("DocStore is not available");
            Document doc = ctx.docStore().get(docId);
            if (doc == null) {
                throw new IllegalArgumentException("Doc not found: " + docId);
            }
            String title = doc.title() + " (" + doc.id() + ")";
            return new ResolvedRef("doc", docId, title, doc.content());
        }
    }

    /** @cache:<id>。 */
    private static final class CacheResolver implements Resolver {

        @Override
        public String prefix() {
            return "@cache";
        }

        @Override
        public ResolvedRef resolve(String cacheId, ResolverContext ctx) {
            if (cacheId.isBlank()) throw new IllegalArgumentException("@cache: id must not be blank");
            if (ctx.cacheDir() == null) throw new IllegalStateException("Cache directory is not available");
            java.nio.file.Path file = ctx.cacheDir().resolve(cacheId + ".txt");
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

    /** @import:<documentId>。 */
    private static final class ImportResolver implements Resolver {

        @Override
        public String prefix() {
            return "@import";
        }

        @Override
        public ResolvedRef resolve(String documentId, ResolverContext ctx) {
            if (documentId.isBlank()) throw new IllegalArgumentException("@import: documentId must not be blank");
            ImportDocumentService service = new ImportDocumentService(ctx.importDir());
            try {
                ImportDocumentReadResult result = service.readDocument(documentId, 0, 30000, true);
                return new ResolvedRef("import", documentId, result.displayName(), result.content());
            } catch (IOException e) {
                throw new IllegalArgumentException("Import document not found: " + documentId, e);
            }
        }
    }
}
