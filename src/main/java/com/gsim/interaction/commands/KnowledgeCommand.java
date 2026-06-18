package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.knowledge.KnowledgeStoreStatus;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;

/**
 * /knowledge — 知识库管理命令。
 */
public class KnowledgeCommand implements InteractionCommand {

    private final SQLiteKnowledgeStore store;

    public KnowledgeCommand(SQLiteKnowledgeStore store) {
        this.store = store;
    }

    @Override
    public String name() { return "knowledge"; }

    @Override
    public String description() { return "知识库管理：/knowledge status"; }

    @Override
    public String usage() {
        return "/knowledge status\n" +
                "  status — 显示知识库状态（文档数、chunk 数、embedding 状态等）";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args.length == 0 || args[0].isBlank()) {
            return InteractionResult.ok(usage());
        }

        String sub = args[0].toLowerCase();
        if ("status".equals(sub)) {
            return doStatus();
        }

        return InteractionResult.ok("未知子命令: " + sub + "\n" + usage());
    }

    private InteractionResult doStatus() {
        KnowledgeStoreStatus s = store.status();

        var sb = new StringBuilder();
        sb.append("========== 知识库状态 ==========\n\n");
        sb.append("DB 路径:       ").append(s.dbPath()).append("\n");
        sb.append("版本:          ").append(s.version()).append("\n");
        sb.append("文档数:        ").append(s.documentCount()).append("\n");
        sb.append("Chunk 数:      ").append(s.chunkCount()).append("\n");
        sb.append("Embedding Profiles: ").append(s.embeddingProfilesCount()).append("\n");
        sb.append("Chunk Embeddings:   ").append(s.chunkEmbeddingsCount()).append("\n");
        sb.append("FTS 可用:      ").append(s.ftsAvailable() ? "是" : "否").append("\n");
        sb.append("Active Profile:     ")
                .append(s.activeEmbeddingProfileId() != null ? s.activeEmbeddingProfileId() : "(无)")
                .append("\n");
        sb.append("默认 Collection:    ").append(s.defaultCollection()).append("\n");
        sb.append("\n");

        if (s.activeEmbeddingProfileId() == null) {
            sb.append("提示: keyword_search 可用。\n");
            sb.append("      配置 embedding 后 knowledge_search 可用。\n");
        }

        sb.append("================================\n");
        return InteractionResult.ok(sb.toString());
    }
}
