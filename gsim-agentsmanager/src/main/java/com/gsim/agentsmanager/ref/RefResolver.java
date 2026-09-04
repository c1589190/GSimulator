package com.gsim.agentsmanager.ref;

import com.gsim.docslib.doc.DocStore;
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
     * 解析 @ 引用并返回统一结果（静态门面，委托 {@link ResolverRegistry} 内置解析器）。
     * <p>
     * 支持的格式：@import:、@world:（三段式与两段式，两段式解析到活跃节点）、@doc:、@cache:，
     * 以及裸引用 {@code nodeId:cpId:key} / {@code cpId:key}。
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
        ResolverContext ctx = ResolverContext.of(worldsDir, activeWorldId, importDir, docStore, cacheDir);
        return ResolverRegistry.createWithBuiltins().resolve(ref, ctx);
    }
}
