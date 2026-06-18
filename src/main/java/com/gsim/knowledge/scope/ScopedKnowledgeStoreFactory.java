package com.gsim.knowledge.scope;

import com.gsim.knowledge.embed.EmbeddingModel;
import com.gsim.knowledge.embed.EmbeddingProfileManager;
import com.gsim.knowledge.search.KnowledgeSearchService;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 root scope 管理 SQLiteKnowledgeStore 和 EmbeddingProfileManager 的工厂。
 *
 * <p>每个 root 有独立的 SQLite db 文件和独立的 embedding_profiles 表。
 * EmbeddingModel 和全局配置（provider/baseUrl/apiKey/model/dimensions）在 root 之间共享。
 */
public class ScopedKnowledgeStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(ScopedKnowledgeStoreFactory.class);

    private final Map<String, SQLiteKnowledgeStore> stores = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingProfileManager> profileManagers = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeSearchService> searchServices = new ConcurrentHashMap<>();

    private final EmbeddingModel embeddingModel;

    public ScopedKnowledgeStoreFactory(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 获取或创建指定 scope 的 SQLiteKnowledgeStore。
     */
    public SQLiteKnowledgeStore getOrCreateStore(KnowledgeScope scope) {
        return stores.computeIfAbsent(scope.rootId(), id -> {
            try {
                Files.createDirectories(scope.knowledgeDbPath().getParent());
                var store = new SQLiteKnowledgeStore(scope.knowledgeDbPath().toString());
                store.initialize();
                log.info("Created knowledge store for root '{}': {}", id, scope.knowledgeDbPath());
                return store;
            } catch (Exception e) {
                throw new RuntimeException("Failed to create knowledge store for root '" + id + "': " + e.getMessage(), e);
            }
        });
    }

    /**
     * 获取或创建指定 scope 的 EmbeddingProfileManager。
     */
    public EmbeddingProfileManager getOrCreateProfileManager(KnowledgeScope scope) {
        return profileManagers.computeIfAbsent(scope.rootId(), id -> {
            var store = getOrCreateStore(scope);
            var pm = new EmbeddingProfileManager(store, embeddingModel);
            if (embeddingModel != null) {
                pm.initialize();
            }
            log.info("Initialized embedding profile manager for root '{}'", id);
            return pm;
        });
    }

    /**
     * 获取或创建指定 scope 的 KnowledgeSearchService。
     */
    public KnowledgeSearchService getOrCreateSearchService(KnowledgeScope scope) {
        return searchServices.computeIfAbsent(scope.rootId(), id -> {
            var store = getOrCreateStore(scope);
            var pm = getOrCreateProfileManager(scope);
            return new KnowledgeSearchService(store, pm);
        });
    }

    /** 获取 scope 对应的 store（可能为 null，如果尚未创建）。 */
    public SQLiteKnowledgeStore getStore(String rootId) {
        return stores.get(rootId);
    }

    /** 获取 scope 对应的 profile manager（可能为 null）。 */
    public EmbeddingProfileManager getProfileManager(String rootId) {
        return profileManagers.get(rootId);
    }

    /** 获取 scope 对应的 search service（可能为 null）。 */
    public KnowledgeSearchService getSearchService(String rootId) {
        return searchServices.get(rootId);
    }

    /** 关闭所有 stores。 */
    public void closeAll() {
        for (var entry : stores.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("Failed to close store for root '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        stores.clear();
        profileManagers.clear();
        searchServices.clear();
    }
}
