package com.gsim.knowledge.store;

/**
 * KnowledgeStore 操作失败时抛出的 unchecked 异常。
 * 调用方可以捕获此异常来区分"无数据"和"存储层故障"。
 */
public class KnowledgeStoreException extends RuntimeException {

    public KnowledgeStoreException(String message) {
        super(message);
    }

    public KnowledgeStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
