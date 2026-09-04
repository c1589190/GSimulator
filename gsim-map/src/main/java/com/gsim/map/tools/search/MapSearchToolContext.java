package com.gsim.map.tools.search;

import com.gsim.agentsmanager.ref.ResolverRegistry;
import com.gsim.core.tools.search.SearchToolContext;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.docslib.doc.DocStore;
import com.gsim.map.service.MapService;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 地图/hex 域搜索工具的共享上下文 — 持有 {@link MapService} 并委托 core 的
 * {@link SearchToolContext}（world/doc/registry/paths 组件）。
 *
 * <p>模块自注册原则：gsim-map 的地图搜索工具不直接依赖 core 的 SearchToolContext 之外
 * 的类型；MapService 仅 gsim-map 可见（避免 core→map 循环）。
 *
 * @param core        core 搜索上下文（wiSupplier/docStore/registry/paths 委托）
 * @param mapService  地图服务（region/hex 域使用）
 */
public record MapSearchToolContext(SearchToolContext core, MapService mapService) {

    public Supplier<WorldInformation> wiSupplier() {
        return core.wiSupplier();
    }

    public DocStore docStore() {
        return core.docStore();
    }

    public ResolverRegistry registry() {
        return core.registry();
    }

    public Path worldsDir() {
        return core.worldsDir();
    }

    public Path importDir() {
        return core.importDir();
    }

    public Path cacheDir() {
        return core.cacheDir();
    }
}
