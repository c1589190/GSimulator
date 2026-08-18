package com.gsim.agent.tools.search;

import com.gsim.core.worldinfo.WorldInformation;
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
 *   <li>{@code registry} — 引用解析注册表（引用解析域使用）</li>
 * </ul>
 *
 * @param wiSupplier 世界信息供应者（可为 null）
 * @param mapService 地图服务（可为 null）
 * @param docStore   文档存储（可为 null）
 * @param registry   引用解析注册表（可为 null）
 */
public record SearchToolContext(
        Supplier<WorldInformation> wiSupplier,
        com.gsim.map.service.MapService mapService,
        com.gsim.core.doc.DocStore docStore,
        com.gsim.core.ref.ResolverRegistry registry) {}
