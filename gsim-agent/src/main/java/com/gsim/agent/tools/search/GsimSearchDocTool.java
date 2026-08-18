package com.gsim.agent.tools.search;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.core.search.SearchEntry;
import java.util.List;
import java.util.Map;

/**
 * 文档域细化搜索工具 {@code gsim_search_doc}。
 *
 * <p><b>新鲜度设计</b>：本工具<em>不读 SkillIndex</em>（唯一写入点是 doc_index 工具，
 * doc_create/doc_write/delete 均不索引，索引必然陈旧），每次调用直读 DocStore
 * （{@code ctx.docStore().list(typeFilter, tagFilter)}），刚创建的文档立即可搜。
 * 语料构造委托给共享的 {@link DocSearchSource}（与 gsim_search 聚合器共用）。
 *
 * <p>语料 = title + summary（正文前 200 字）+ tags（不含全文）；key = {@code @doc:<docId>}，
 * 与 RefResolver 的 {@code @doc:} 约定一致，结果可直接解析。
 *
 * <p><b>type/tag 过滤参数的读取方式（约定）</b>：沿用 AbstractSearchTool 提供的三参钩子
 * {@link #buildEntries(String, String, ToolCall)}（模板 execute 在语料构造阶段传入原始
 * ToolCall，供域特定过滤使用——与 gsim_search_world 的 checkpointId 过滤同一机制），
 * 在钩子内读取 {@code type}/{@code tag} 参数并委托给共享语料源；两参抽象方法以
 * 无过滤参数委托。不覆写 execute，keywords 必填校验由共享模板负责。
 */
public final class GsimSearchDocTool extends AbstractSearchTool {

    private final DocSearchSource source;

    /**
     * 创建文档搜索工具。
     *
     * @param ctx 共享搜索上下文（仅使用 docStore；其余字段可为 null）
     */
    public GsimSearchDocTool(SearchToolContext ctx) {
        super(ctx);
        this.source = new DocSearchSource(ctx);
    }

    @Override
    public String name() {
        return "gsim_search_doc";
    }

    @Override
    public String description() {
        return "Full-text search across documents (title + summary + tags). "
                + "Reads DocStore directly — freshly created documents are searchable without doc_index. "
                + "Optional type restricts to a DocType key (character/skill/world_state/other ...); "
                + "optional tag restricts to documents carrying that tag; supports pagination via limit/offset.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> base = super.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = new java.util.LinkedHashMap<>((Map<String, Object>) base.get("properties"));
        props.put(
                "type",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional DocType key to filter results (character/skill/world_state/other ...)"));
        props.put(
                "tag",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional tag to filter results (exact match, case-insensitive)"));
        return Map.of("type", "object", "properties", props, "required", List.of("keywords"));
    }

    @Override
    public String domain() {
        return "doc";
    }

    @Override
    protected String defaultNodeId(String worldId) {
        // 文档全局可见，不随节点作用域变化
        return null;
    }

    @Override
    protected List<SearchEntry> buildEntries(String worldId, String effectiveNodeId, ToolCall call) {
        // 文档全局可见，worldId/effectiveNodeId 均不使用
        return source.build(worldId, effectiveNodeId, call.param("type"), call.param("tag"));
    }

    @Override
    protected List<SearchEntry> buildEntries(String worldId, String effectiveNodeId) {
        return source.build(worldId, effectiveNodeId, null, null);
    }
}
