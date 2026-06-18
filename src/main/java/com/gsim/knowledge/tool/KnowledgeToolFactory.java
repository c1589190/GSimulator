package com.gsim.knowledge.tool;

import com.gsim.knowledge.*;
import com.gsim.knowledge.chunk.Chunker;
import com.gsim.knowledge.embed.EmbeddingModel;
import com.gsim.knowledge.embed.EmbeddingProfile;
import com.gsim.knowledge.embed.EmbeddingProfileManager;
import com.gsim.knowledge.embed.EmbeddingVector;
import com.gsim.knowledge.embed.VectorCodec;
import com.gsim.knowledge.search.KnowledgeSearchService;
import com.gsim.knowledge.store.KnowledgeStore;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Knowledge Tool 工厂 — 创建所有 Agent 知识库工具。
 */
public class KnowledgeToolFactory {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeToolFactory.class);

    private final KnowledgeStore store;
    private final KnowledgeSearchService searchService;
    private final EmbeddingProfileManager profileManager;
    private final Chunker chunker;

    public KnowledgeToolFactory(KnowledgeStore store, KnowledgeSearchService searchService,
                                 EmbeddingProfileManager profileManager) {
        this.store = store;
        this.searchService = searchService;
        this.profileManager = profileManager;
        this.chunker = new Chunker();
    }

    /** 返回所有 8 个 knowledge tools。 */
    public List<AgentTool> createAll() {
        return List.of(
                new KeywordSearchTool(),
                new KnowledgeSearchTool(),
                new KnowledgeGetChunkTool(),
                new KnowledgeGetDocumentTool(),
                new KnowledgeUpsertTool(),
                new KnowledgeUpdateTool(),
                new KnowledgeDeleteTool(),
                new KnowledgeEmbedMissingTool()
        );
    }

    // ---- Tool implementations ----

    private class KeywordSearchTool implements AgentTool {
        @Override public String name() { return "keyword_search"; }
        @Override public String description() {
            return "关键词检索知识库，永远可用。使用 FTS5 + LIKE 搜索。参数: query(必填), collection(可选,默认default), topK(可选,默认5,最大20)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            String query = call.param("query", "");
            if (query.isBlank()) return ToolResult.fail(name(), "query is required");
            String collection = call.param("collection", "default");
            int topK = parseInt(call.param("topK"), 5, 1, 20);

            List<KnowledgeSearchResult> results = store.searchKeyword(query, collection, topK);
            return toToolResult(results);
        }
    }

    private class KnowledgeSearchTool implements AgentTool {
        @Override public String name() { return "knowledge_search"; }
        @Override public String description() {
            return "语义检索知识库，需要 active embedding profile。如果没有 embedding profile 或没有 embeddings，返回结构化错误。参数: query(必填), collection(可选,默认default), topK(可选,默认5,最大20)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            String query = call.param("query", "");
            if (query.isBlank()) return ToolResult.fail(name(), "query is required");
            String collection = call.param("collection", "default");
            int topK = parseInt(call.param("topK"), 5, 1, 20);

            KnowledgeSearchResponse resp = searchService.semanticSearch(query, collection, topK);
            if (!resp.success()) {
                return ToolResult.fail(name(), "[" + resp.errorCode() + "] " + resp.error());
            }
            return toToolResult(resp.items());
        }
    }

    private class KnowledgeGetChunkTool implements AgentTool {
        @Override public String name() { return "knowledge_get_chunk"; }
        @Override public String description() {
            return "获取指定 chunk 的完整文本和元数据。参数: chunkId(必填)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            String chunkId = call.param("chunkId", "");
            if (chunkId.isBlank()) return ToolResult.fail(name(), "chunkId is required");

            Optional<KnowledgeChunk> chunk = store.getChunk(chunkId);
            if (chunk.isEmpty()) {
                return ToolResult.fail(name(), "CHUNK_NOT_FOUND: " + chunkId);
            }
            KnowledgeChunk c = chunk.get();
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item(c.title(), c.chunkId(),
                            "docId=" + c.docId() + " collection=" + c.collection()
                            + " index=" + c.chunkIndex() + "\n" + c.text(), 1.0)));
        }
    }

    private class KnowledgeGetDocumentTool implements AgentTool {
        @Override public String name() { return "knowledge_get_document"; }
        @Override public String description() {
            return "获取指定文档的元数据和正文（正文过长则截断）。参数: docId(必填)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            String docId = call.param("docId", "");
            if (docId.isBlank()) return ToolResult.fail(name(), "docId is required");

            Optional<KnowledgeDocument> doc = store.getDocument(docId);
            if (doc.isEmpty()) {
                return ToolResult.fail(name(), "DOCUMENT_NOT_FOUND: " + docId);
            }
            KnowledgeDocument d = doc.get();
            String content = d.content().length() > 2000
                    ? d.content().substring(0, 2000) + "...(truncated, fullRef=" + docId + ")"
                    : d.content();
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item(d.title(), d.docId(),
                            "collection=" + d.collection() + " sourceType=" + d.sourceType()
                            + " sourceUri=" + d.sourceUri() + "\n" + content, 1.0)));
        }
    }

    private class KnowledgeUpsertTool implements AgentTool {
        @Override public String name() { return "knowledge_upsert"; }
        @Override public String description() {
            return "保存新资料到知识库。Agent 用于长期保存设定、资料、摘要、证据片段。参数: title(必填), content(必填), collection(可选,默认default), sourceType(可选: web|manual_note|branch_output|agent_note), sourceUri(可选), metadata(可选JSON对象)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            String title = call.param("title", "");
            if (title.isBlank()) return ToolResult.fail(name(), "title is required");
            String content = call.param("content", "");
            if (content.isBlank()) return ToolResult.fail(name(), "content is required");
            String collection = call.param("collection", "default");
            String sourceType = call.param("sourceType", "agent_note");
            String sourceUri = call.param("sourceUri", "");

            Map<String, String> metadata = null;
            String metadataStr = call.param("metadata", "");
            if (!metadataStr.isBlank()) {
                try {
                    metadata = JsonUtils.fromJson(metadataStr,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
                } catch (Exception e) {
                    log.warn("Failed to parse metadata JSON: {}", metadataStr);
                }
            }

            KnowledgeDocumentInput input = new KnowledgeDocumentInput(
                    title, content, collection, sourceType, sourceUri, metadata);
            KnowledgeUpsertResult result = store.upsert(input);

            if (!result.success()) {
                return ToolResult.fail(name(), result.error());
            }

            // 如果有 active profile，自动生成 embeddings
            Optional<EmbeddingProfile> activeProfile = profileManager.getActiveProfile();
            if (activeProfile.isPresent() && activeProfile.get().isAvailable()) {
                EmbeddingModel model = profileManager.getEmbeddingModel();
                if (model != null && model.isAvailable()) {
                    try {
                        List<String> chunkIds = store.findChunksMissingEmbedding(
                                collection, activeProfile.get().profileId());
                        embedChunks(chunkIds, model, activeProfile.get());
                        // 重新检查状态
                        return ToolResult.ok(name(), List.of(
                                new ToolResult.Item(title, result.docId(),
                                        "status=OK docId=" + result.docId()
                                        + " chunks=" + result.chunksCreated()
                                        + " embeddings=" + chunkIds.size()
                                        + " profile=" + activeProfile.get().profileId(), 1.0)));
                    } catch (Exception e) {
                        log.warn("Auto-embedding failed during upsert: {}", e.getMessage());
                    }
                }
            }

            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item(title, result.docId(),
                            "status=" + result.status() + " docId=" + result.docId()
                            + " chunks=" + result.chunksCreated(), 1.0)));
        }
    }

    private class KnowledgeUpdateTool implements AgentTool {
        @Override public String name() { return "knowledge_update"; }
        @Override public String description() {
            return "更新已有文档。删除旧 chunks/embeddings，重建新 chunks。参数: docId(必填), title(必填), content(必填), collection(可选), sourceType(可选), sourceUri(可选), metadata(可选)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            String docId = call.param("docId", "");
            if (docId.isBlank()) return ToolResult.fail(name(), "docId is required");
            String title = call.param("title", "");
            if (title.isBlank()) return ToolResult.fail(name(), "title is required");
            String content = call.param("content", "");
            if (content.isBlank()) return ToolResult.fail(name(), "content is required");
            String collection = call.param("collection", "default");
            String sourceType = call.param("sourceType", "agent_note");
            String sourceUri = call.param("sourceUri", "");

            KnowledgeDocumentInput input = new KnowledgeDocumentInput(
                    title, content, collection, sourceType, sourceUri, null);
            KnowledgeUpdateResult result = store.update(docId, input);

            if (!result.success()) {
                return ToolResult.fail(name(), "[" + result.status() + "] " + result.error());
            }

            // 如果有 active profile，自动重嵌入
            Optional<EmbeddingProfile> activeProfile = profileManager.getActiveProfile();
            if (activeProfile.isPresent() && activeProfile.get().isAvailable()) {
                EmbeddingModel model = profileManager.getEmbeddingModel();
                if (model != null && model.isAvailable()) {
                    try {
                        List<String> chunkIds = store.findChunksMissingEmbedding(
                                collection, activeProfile.get().profileId());
                        embedChunks(chunkIds, model, activeProfile.get());
                    } catch (Exception e) {
                        log.warn("Auto-embedding failed during update: {}", e.getMessage());
                    }
                }
            }

            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item(title, docId,
                            "status=" + result.status()
                            + " oldChunksDeleted=" + result.oldChunksDeleted()
                            + " oldEmbeddingsDeleted=" + result.oldEmbeddingsDeleted()
                            + " newChunksCreated=" + result.newChunksCreated(), 1.0)));
        }
    }

    private class KnowledgeDeleteTool implements AgentTool {
        @Override public String name() { return "knowledge_delete"; }
        @Override public String description() {
            return "删除文档及其所有 chunks/embeddings。参数: docId(必填)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            String docId = call.param("docId", "");
            if (docId.isBlank()) return ToolResult.fail(name(), "docId is required");

            KnowledgeDeleteResult result = store.delete(docId);
            if (!result.success()) {
                return ToolResult.fail(name(), result.error());
            }
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("deleted", docId,
                            "chunksDeleted=" + result.chunksDeleted()
                            + " embeddingsDeleted=" + result.embeddingsDeleted(), 1.0)));
        }
    }

    private class KnowledgeEmbedMissingTool implements AgentTool {
        @Override public String name() { return "knowledge_embed_missing"; }
        @Override public String description() {
            return "为缺少 embedding 的 chunks 批量生成向量。不会自动全库重嵌入。参数: collection(可选,默认default), profileId(可选,默认active)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            String collection = call.param("collection", "default");
            Optional<EmbeddingProfile> activeProfile = profileManager.getActiveProfile();
            if (activeProfile.isEmpty() || !activeProfile.get().isAvailable()) {
                return ToolResult.fail(name(), "NO_ACTIVE_EMBEDDING_PROFILE: 没有可用的 embedding profile。");
            }
            EmbeddingProfile profile = activeProfile.get();
            EmbeddingModel model = profileManager.getEmbeddingModel();
            if (model == null || !model.isAvailable()) {
                return ToolResult.fail(name(), "EMBEDDING_PROVIDER_UNAVAILABLE");
            }

            List<String> missing = store.findChunksMissingEmbedding(collection, profile.profileId());
            if (missing.isEmpty()) {
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item("complete", collection,
                                "所有 chunks 已有 profile " + profile.profileId() + " 的 embeddings", 1.0)));
            }

            int embedded = embedChunks(missing, model, profile);
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("embed_missing", collection,
                            "embedded=" + embedded + " missing=" + missing.size()
                            + " profile=" + profile.profileId(), 1.0)));
        }
    }

    // ---- Helpers ----

    private int embedChunks(List<String> chunkIds, EmbeddingModel model, EmbeddingProfile profile) {
        if (chunkIds.isEmpty()) return 0;
        String now = Instant.now().toString();
        List<KnowledgeStore.ChunkEmbeddingRow> rows = new ArrayList<>();

        // 批量读取 chunk 文本
        List<String> texts = new ArrayList<>();
        List<String> contentHashes = new ArrayList<>();
        for (String chunkId : chunkIds) {
            Optional<KnowledgeChunk> chunk = store.getChunk(chunkId);
            if (chunk.isPresent()) {
                texts.add(chunk.get().text());
                contentHashes.add(chunk.get().contentHash());
            }
        }

        if (texts.isEmpty()) return 0;

        try {
            List<EmbeddingVector> vectors = model.embedAll(texts);
            for (int i = 0; i < vectors.size() && i < chunkIds.size(); i++) {
                EmbeddingVector vec = vectors.get(i);
                byte[] blob = VectorCodec.encodeFloat32(vec.values());
                rows.add(new KnowledgeStore.ChunkEmbeddingRow(
                        chunkIds.get(i), profile.profileId(), vec.dimensions(),
                        blob, contentHashes.get(i), now));
            }
            return store.writeEmbeddings(rows);
        } catch (Exception e) {
            log.error("embedChunks failed: {}", e.getMessage());
            return 0;
        }
    }

    private ToolResult toToolResult(List<KnowledgeSearchResult> results) {
        if (results.isEmpty()) {
            return ToolResult.ok("keyword_search", List.of(
                    new ToolResult.Item("(无结果)", "", "未找到匹配的结果。", 0)));
        }
        List<ToolResult.Item> items = results.stream()
                .map(r -> new ToolResult.Item(r.title(), r.chunkId(),
                        "docId=" + r.docId() + " sourceUri=" + (r.sourceUri() != null ? r.sourceUri() : "")
                        + " collection=" + r.collection() + " score=" + String.format("%.4f", r.finalScore())
                        + " mode=" + r.searchMode() + "\n" + r.snippet(),
                        r.finalScore()))
                .toList();
        return ToolResult.ok(results.get(0).searchMode(), items);
    }

    private static int parseInt(String s, int def, int min, int max) {
        try {
            int v = Integer.parseInt(s);
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
