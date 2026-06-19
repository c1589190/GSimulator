package com.gsim.importing.tool;

import com.gsim.importing.ImportDocumentService;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * import_document_search — 在 import 文档中搜索关键词。
 * 支持按 documentId、source 过滤，可选大小写敏感。
 * 不依赖 active root，任意时刻可用。
 */
public class ImportDocumentSearchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ImportDocumentSearchTool.class);
    public static final String NAME = "import_document_search";

    private final ImportDocumentService service;

    public ImportDocumentSearchTool(ImportDocumentService service) {
        this.service = service;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "在 import 文档中搜索关键词。参数: query(必填), documentId(可选, 限定单个文件), source(可选, LOCAL_IMPORT/WIKI_DOWNLOADED), maxResults(可选, 默认10), contextChars(可选, 默认300), caseSensitive(可选, 默认false)。"
                + "返回 documentId, source, displayName, offset, preview。"
                + "不依赖 active root，任意节点可用。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String query = call.param("query", "");
        if (query.isBlank()) {
            return ToolResult.fail(NAME, "query is required");
        }

        String documentId = call.param("documentId", "");
        if (documentId.isBlank()) documentId = null;

        String source = call.param("source", "");
        if (source.isBlank()) source = null;

        int maxResults = parseInt(call.param("maxResults"), 10, 1, 50);
        int contextChars = parseInt(call.param("contextChars"), 300, 50, 2000);
        boolean caseSensitive = "true".equalsIgnoreCase(call.param("caseSensitive", "false"));

        try {
            List<ImportDocumentService.ImportDocumentSearchMatch> matches =
                    service.searchDocuments(query, documentId, source, maxResults, contextChars, caseSensitive);

            if (matches.isEmpty()) {
                return ToolResult.ok(NAME, List.of(
                        new ToolResult.Item("(无结果)", "",
                                "未在 import 文档中找到「" + query + "」。", 0)));
            }

            List<ToolResult.Item> items = matches.stream()
                    .map(m -> new ToolResult.Item(
                            m.displayName() + " [" + m.source() + "] @" + m.offset(),
                            m.documentId(),
                            "offset=" + m.offset() + " source=" + m.source() + "\n" + m.preview(),
                            1.0))
                    .toList();

            return ToolResult.ok(NAME, items);

        } catch (Exception e) {
            log.error("import_document_search failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "IMPORT_SEARCH_FAILED: " + e.getMessage());
        }
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
