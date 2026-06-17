package com.gsim.chroma;

import java.util.List;

/**
 * ChromaDB 客户端统一接口。
 * 所有 ChromaDB 操作必须通过此接口，不允许 Agent 直接操作数据库。
 */
public interface ChromaClient {

    /**
     * 检查 ChromaDB 是否可用。
     */
    boolean isAvailable();

    /**
     * 查询指定 collection。
     */
    ChromaQueryResponse query(ChromaQueryRequest request);

    /**
     * 批量写入文档。
     */
    int addDocuments(String collection, List<ChromaDocument> documents);

    /**
     * 列出所有 collections。
     */
    List<String> listCollections();
}
