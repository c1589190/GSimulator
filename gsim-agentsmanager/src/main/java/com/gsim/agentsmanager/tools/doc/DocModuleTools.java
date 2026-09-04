package com.gsim.agentsmanager.tools.doc;

import com.gsim.agentsmanager.event.AgentProgressSink;
import com.gsim.agentsmanager.tool.ToolRegistry;
import com.gsim.docslib.doc.DocCacheManager;
import com.gsim.docslib.doc.DocStore;

/**
 * agentsmanager 模块文档工具注册器 — 注册 7 个纯文档工具（模块自注册原则）。
 *
 * <p>文档存储/缓存由 gsim-docslib 提供；知识库/导入/引用工具由 gsim-core 的 CoreModuleTools 注册。
 */
public final class DocModuleTools {

    private DocModuleTools() {}

    /** 注册 7 个纯文档工具。 */
    public static void registerDocTools(
            ToolRegistry toolRegistry,
            DocStore docStore,
            DocCacheManager docCacheManager,
            AgentProgressSink progressSink) {
        toolRegistry.register(new DocListTool(docStore));
        toolRegistry.register(new DocReadTool(docStore, docCacheManager));
        toolRegistry.register(new DocCreateTool(docStore, docCacheManager, progressSink));
        toolRegistry.register(new DocWriteTool(docStore, docCacheManager, progressSink));
        toolRegistry.register(new DocCropTool(docStore, docCacheManager));
        toolRegistry.register(new DocTemplateTool(docStore, docCacheManager));
        toolRegistry.register(new DocDeleteTool(docStore));
    }
}
