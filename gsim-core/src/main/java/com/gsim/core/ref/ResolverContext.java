package com.gsim.core.ref;

import com.gsim.docslib.doc.DocStore;
import com.gsim.core.worldinfo.loader.WorldManager;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 单次引用解析的上下文 — 由调用方（工具层）按次构造，携带各解析器按需取用的业务对象。
 *
 * @param worldsDir    世界目录根路径（@world/裸引用用）
 * @param activeWorldId 当前（本次调用）活跃世界 ID（@world/裸引用/gsimap 用）
 * @param importDir    导入文档目录（@import 用）
 * @param docStore     文档存储（@doc 用）
 * @param cacheDir     缓存目录（@cache 用）
 * @param worldManager 世界读取入口供应器（@world/裸引用用；默认按 worldsDir 现建，与历史行为一致）
 */
public record ResolverContext(
        Path worldsDir,
        String activeWorldId,
        Path importDir,
        DocStore docStore,
        Path cacheDir,
        Supplier<WorldManager> worldManager) {

    public ResolverContext {
        Objects.requireNonNull(worldsDir, "worldsDir");
    }

    /**
     * 便捷工厂：按 worldsDir 构造默认的 {@link WorldManager} 供应器（每次现建，只读、无状态）。
     */
    public static ResolverContext of(
            Path worldsDir, String activeWorldId, Path importDir, DocStore docStore, Path cacheDir) {
        return new ResolverContext(
                worldsDir, activeWorldId, importDir, docStore, cacheDir, () -> new WorldManager(worldsDir));
    }
}
