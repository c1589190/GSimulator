package com.gsim.core.tools.bridge;

import com.gsim.agentsmanager.config.CoreConfig;
import com.gsim.agentsmanager.ref.InlineRefResolver;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.docslib.doc.DocCacheManager;
import com.gsim.docslib.doc.DocStore;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 注册 worldinfo 工具的上下文。
 *
 * <p>{@code worldInfoSupplier} 为 worldInfo 供应器——app 侧以 {@code () -> worldInfo}
 * 传入，工具注册后可看到节点创建/世界切换后重建的最新实例。其返回可能为 null
 * （Bootstrap 未产出时）；null 时 registerWorldInfoTools 仅注册 WorldList/WorldCreate
 * （与现状行为一致）。
 */
public record WorldInfoToolContext(
        Path worldsDir,
        Supplier<WorldInformation> worldInfoSupplier,
        Supplier<String> activeWorldId,
        DocCacheManager docCacheManager,
        Runnable onNodeChanged,
        DocStore docStore,
        InlineRefResolver inlineRefResolver,
        CoreConfig coreConfig) {}
