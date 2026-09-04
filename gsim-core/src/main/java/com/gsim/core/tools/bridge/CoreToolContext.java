package com.gsim.core.tools.bridge;

import com.gsim.agentsmanager.event.AgentProgressSink;
import com.gsim.agentsmanager.ref.ResolverRegistry;
import com.gsim.core.embedding.EmbeddingClient;
import com.gsim.core.skill.SkillIndex;
import com.gsim.docslib.doc.DocCacheManager;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.importing.ImportDocumentService;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 注册 core 业务工具所需的全部业务对象（由 gsim-app 组装后传入）。
 *
 * <p>gsim-core 只提供业务接口（DocStore、ImportDocumentService 等），
 * 工具包装（AgentTool 实现）全部在 gsim-agent 完成——本 record 是两者之间的桥。
 */
public record CoreToolContext(
        Path worldsDir,
        Path docsDir,
        Path importDir,
        ImportDocumentService importDocService,
        DocStore docStore,
        DocCacheManager docCacheManager,
        SkillIndex docIndex,
        EmbeddingClient embeddingClient,
        Supplier<String> activeWorldId,
        AgentProgressSink progressSink,
        ResolverRegistry resolverRegistry) {}
