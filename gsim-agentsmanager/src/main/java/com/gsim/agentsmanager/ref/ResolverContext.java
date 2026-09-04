package com.gsim.agentsmanager.ref;

import com.gsim.docslib.doc.DocStore;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 单次引用解析的上下文 — 由调用方（工具层）按次构造，携带各解析器按需取用的业务对象。
 *
 * <p>@world:/裸引用 的世界读取逻辑由 gsim-core 的 WorldResolver 持有（B 方案依赖反转），
 * 本上下文不再携带 WorldManager。
 *
 * @param worldsDir     世界目录根路径（@world/裸引用/gsimap 用）
 * @param activeWorldId 当前（本次调用）活跃世界 ID（@world/裸引用/gsimap 用）
 * @param importDir     导入文档目录（@import 用）
 * @param docStore      文档存储（@doc 用）
 * @param cacheDir      缓存目录（@cache 用）
 */
public record ResolverContext(Path worldsDir, String activeWorldId, Path importDir, DocStore docStore, Path cacheDir) {

    public ResolverContext {
        Objects.requireNonNull(worldsDir, "worldsDir");
    }

    /**
     * 便捷工厂：按 worldsDir 构造上下文。
     */
    public static ResolverContext of(
            Path worldsDir, String activeWorldId, Path importDir, DocStore docStore, Path cacheDir) {
        return new ResolverContext(worldsDir, activeWorldId, importDir, docStore, cacheDir);
    }
}
