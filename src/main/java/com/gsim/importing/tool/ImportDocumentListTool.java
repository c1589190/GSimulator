package com.gsim.importing.tool;

import com.gsim.importing.ImportDocumentService;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * import_document_list — 列出 import 目录下所有可读文档（LOCAL_IMPORT + WIKI_DOWNLOADED）。
 * 不依赖 active root，任意时刻可用。
 */
public class ImportDocumentListTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ImportDocumentListTool.class);
    public static final String NAME = "import_document_list";

    private final ImportDocumentService service;

    public ImportDocumentListTool(ImportDocumentService service) {
        this.service = service;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "列出 import 目录下所有可读文档。包括用户手动放入的本地 txt/md 文件(LOCAL_IMPORT)和联网/wiki下载的缓存文件(WIKI_DOWNLOADED)。"
                + "不依赖 active root，任意节点可用。"
                + "返回 documentId, source, displayName, relativePath, sizeBytes, charCount, lastModified。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            List<ImportDocumentService.ImportDocumentInfo> docs = service.listDocuments();

            if (docs.isEmpty()) {
                return ToolResult.ok(NAME, List.of(
                        new ToolResult.Item("(无文档)", "",
                                "import 目录下没有找到可读文档（.txt/.md/.markdown）。"
                                        + "请将文档放入 import/ 目录。",
                                0)));
            }

            List<ToolResult.Item> items = docs.stream()
                    .map(d -> new ToolResult.Item(
                            d.displayName() + " [" + d.source() + "]",
                            d.documentId(),
                            "source=" + d.source()
                                    + " size=" + d.sizeBytes() + " chars=" + d.charCount()
                                    + " modified=" + d.lastModified(),
                            1.0))
                    .toList();

            return ToolResult.ok(NAME, items);

        } catch (Exception e) {
            log.error("import_document_list failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "IMPORT_LIST_FAILED: " + e.getMessage());
        }
    }
}
