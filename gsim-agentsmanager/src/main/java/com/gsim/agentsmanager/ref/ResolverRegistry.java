package com.gsim.agentsmanager.ref;

import com.gsim.agentsmanager.ref.RefResolver.ResolvedRef;
import com.gsim.docslib.doc.Document;
import com.gsim.docslib.importing.ImportDocumentService;
import com.gsim.docslib.importing.ImportDocumentService.ImportDocumentReadResult;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 可插拔引用解析注册中心 — 将 {@code @world:/@doc:/@cache:/@import:/gsimap:/裸引用} 统一分发到对应
 * {@link Resolver}。
 *
 * <p>内置 resolver（{@link #createWithBuiltins()}）覆盖 {@code @doc}、{@code @cache}、
 * {@code @import}。<b>{@code @world} 与裸引用由 gsim-core 的 {@code WorldResolver} 实现
 * （B 方案依赖反转：core 实现 agentsmanager 的 {@link Resolver} 接口，app 装配注册）</b>。
 * {@code gsimap:} 前缀由应用层提供 {@code GsimapResolver}（依赖 MapService），装配时注册。
 *
 * <p>注册中心无状态、线程安全（只读的 resolver 列表）；每次解析的上下文经
 * {@link ResolverContext} 按次传入。
 */
public final class ResolverRegistry {

    private static final String GSIMAP_PREFIX = "gsimap";

    private final List<Resolver> resolvers = new ArrayList<>();

    private ResolverRegistry() {}

    /**
     * 创建注册中心并注册内置 resolver（@doc/@cache/@import）。
     *
     * <p>@world:/裸引用 不在此注册——由 gsim-core 的 WorldResolver 实现并在装配时注册。
     */
    public static ResolverRegistry createWithBuiltins() {
        ResolverRegistry registry = new ResolverRegistry();
        registry.register(new DocResolver());
        registry.register(new CacheResolver());
        registry.register(new ImportResolver());
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
