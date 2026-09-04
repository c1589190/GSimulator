package com.gsim.agent.tools.search;

import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.doc.DocType;
import com.gsim.docslib.doc.Document;
import com.gsim.core.search.SearchEntry;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档域共享语料源（gsim_search_doc 与 gsim_search 聚合器共用）。
 *
 * <p>语料语义（从 {@link GsimSearchDocTool} 抽取，行为完全一致）：<b>不读 SkillIndex</b>
 * （唯一写入点是 doc_index 工具，doc_create/doc_write/delete 均不索引，索引必然陈旧），
 * 每次调用直读 DocStore（{@code ctx.docStore().list(typeFilter, tagFilter)}），刚创建的
 * 文档立即可搜。语料 = title + summary（正文前 200 字）+ tags（不含全文）；key =
 * {@code @doc:<docId>}，与 RefResolver 的 {@code @doc:} 约定一致；sortKey = updatedAt。
 *
 * <p>包可见：仅限 search 包内工具/聚合器直接调用，避免 ToolRegistry 往返解析。
 */
final class DocSearchSource {

    private final SearchToolContext ctx;

    DocSearchSource(SearchToolContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 构建文档搜索语料（文档全局可见，worldId/effectiveNodeId 不使用）。
     *
     * @param worldId         世界 ID（不使用）
     * @param effectiveNodeId 生效节点 ID（不使用）
     * @param typeParam       可选的 DocType key 过滤（null/空白表示不过滤；未知 key 落入 OTHER）
     * @param tagParam        可选的标签过滤（null/空白表示不过滤）
     * @return 搜索语料列表（docStore 为 null 时为空）
     */
    List<SearchEntry> build(String worldId, String effectiveNodeId, String typeParam, String tagParam) {
        DocStore store = ctx.docStore();
        List<SearchEntry> entries = new ArrayList<>();
        if (store == null) return entries;

        DocType typeFilter = null;
        if (typeParam != null && !typeParam.isBlank()) {
            typeFilter = DocType.fromKey(typeParam.trim());
        }
        String tagFilter = (tagParam == null || tagParam.isBlank()) ? null : tagParam.trim();

        for (Document doc : store.list(typeFilter, tagFilter)) {
            String tags = String.join(" ", doc.tags());
            String text = doc.title() + " " + doc.summary() + (tags.isEmpty() ? "" : " " + tags);
            entries.add(new SearchEntry(text, "@doc:" + doc.id(), doc.updatedAt()));
        }
        return entries;
    }
}
