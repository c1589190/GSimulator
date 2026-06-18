package com.gsim.knowledge.scope;

import java.nio.file.Path;

/**
 * 知识库作用域 — 绑定到一个 root workspace。
 */
public record KnowledgeScope(
        String rootId,
        Path rootDir,
        Path knowledgeDbPath
) {
    public static KnowledgeScope of(Path dataRoot, String rootId) {
        Path rootDir = dataRoot.resolve("worlds").resolve(rootId);
        Path dbPath = rootDir.resolve("knowledge").resolve("gsim.db");
        return new KnowledgeScope(rootId, rootDir, dbPath);
    }
}
