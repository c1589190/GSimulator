package com.gsim.agent.tools.search;

import com.gsim.core.ref.ResolverContext;
import com.gsim.core.worldinfo.WorldInformation;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 领域搜索工具的共享依赖上下文。
 *
 * <p>由装配层（Main / T8 接线）构造一次，按 {@code GsimRequestContext.worldId()}
 * 解析并缓存对应世界的 {@link WorldInformation}，传递给各领域搜索工具共享使用。
 *
 * <p>各组件按域按需取用，未使用的组件可以为 null：
 * <ul>
 *   <li>{@code wiSupplier} — 世界信息供应者（world / element 域使用）</li>
 *   <li>{@code mapService} — 地图服务（region / hex 域使用）</li>
 *   <li>{@code docStore} — 文档存储（doc 域使用）</li>
 *   <li>{@code registry} — 引用解析注册表（gsim_search 地址模式使用）</li>
 *   <li>{@code worldsDir}/{@code importDir}/{@code cacheDir} — 地址解析路径
 *       （gsim_search 地址模式构造 {@link ResolverContext} 使用；四参兼容构造下为 null，
 *       此时除 {@code @doc:} 外的地址解析不可用，自动回退关键词模式）</li>
 * </ul>
 *
 * @param wiSupplier 世界信息供应者（可为 null）
 * @param mapService 地图服务（可为 null）
 * @param docStore   文档存储（可为 null）
 * @param registry   引用解析注册表（可为 null）
 * @param worldsDir  世界目录根路径（可为 null）
 * @param importDir  导入文档目录（可为 null）
 * @param cacheDir   缓存目录（可为 null）
 */
public record SearchToolContext(
        Supplier<WorldInformation> wiSupplier,
        com.gsim.map.service.MapService mapService,
        com.gsim.docslib.doc.DocStore docStore,
        com.gsim.core.ref.ResolverRegistry registry,
        Path worldsDir,
        Path importDir,
        Path cacheDir) {

    /**
     * 兼容构造（T4 原始四组件）：路径组件为 null —— 地址解析（{@code @world:}/
     * {@code @cache:}/{@code @import:}/裸引用）不可用，gsim_search 自动回退关键词模式；
     * {@code @doc:} 解析仍可用（仅需 docStore）。
     */
    public SearchToolContext(
            Supplier<WorldInformation> wiSupplier,
            com.gsim.map.service.MapService mapService,
            com.gsim.docslib.doc.DocStore docStore,
            com.gsim.core.ref.ResolverRegistry registry) {
        this(wiSupplier, mapService, docStore, registry, null, null, null);
    }
}
