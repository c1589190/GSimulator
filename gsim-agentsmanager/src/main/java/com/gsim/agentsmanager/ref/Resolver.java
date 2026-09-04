package com.gsim.agentsmanager.ref;

import com.gsim.agentsmanager.ref.RefResolver.ResolvedRef;

/**
 * 引用解析器插件接口 — 按前缀（prefix）分发到对应后端。
 *
 * <p>前缀约定：
 * <ul>
 *   <li>{@code @world} — World 元素（三段式 {@code @world:nodeId:cpId:key} 与两段式
 *       {@code @world:cpId:key}，两段式解析到活跃节点）</li>
 *   <li>{@code @doc} — Doc/Board 文档</li>
 *   <li>{@code @cache} — 缓存文本</li>
 *   <li>{@code @import} — 导入文档</li>
 *   <li>{@code gsimap} — 地图实体（region/hex/city/terrain，由 gsim-agent/gsim-app 层提供，
 *       因 gsim-core 不依赖 gsim-map）</li>
 *   <li>{@code ""} — 裸引用 {@code nodeId:cpId:key} / {@code cpId:key}（按 @world 语义解析）</li>
 * </ul>
 *
 * <p>解析器无状态：每次解析所需的上下文（worldsDir、活跃 worldId 等）由调用方
 * 通过 {@link ResolverContext} 按次传入。
 */
public interface Resolver {

    /** 引用前缀（如 "@world"、"gsimap"），裸引用解析器返回空串。 */
    String prefix();

    /**
     * 解析去掉前缀与冒号后的路径（裸引用时 path 为完整引用串）。
     *
     * @param path 去掉 {@code prefix + ":"} 后的剩余路径
     * @param ctx  本次解析上下文
     * @return 统一解析结果
     * @throws IllegalArgumentException 引用格式无法识别或资源不存在时抛出
     * @throws IllegalStateException    必要的上下文（如活跃 worldId）未配置时抛出
     */
    ResolvedRef resolve(String path, ResolverContext ctx);
}
