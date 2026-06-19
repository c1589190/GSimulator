package com.gsim.importing.tool;

import com.gsim.importing.ImportDocumentService;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * import_document_read — 分段读取 import 文档内容。
 * 支持 offset/limit 分页和 full 全文模式（上限 30000 字符）。
 * 不依赖 active root，任意时刻可用。
 */
public class ImportDocumentReadTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ImportDocumentReadTool.class);
    public static final String NAME = "import_document_read";

    private final ImportDocumentService service;

    public ImportDocumentReadTool(ImportDocumentService service) {
        this.service = service;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "分段读取 import 文档。参数: documentId(必填, 来自 import_document_list), offset(可选, 默认0), limit(可选, 默认8000), full(可选, 默认false, 上限30000)。"
                + "返回 originalLength, offset, limit, returnedRange, truncated, nextOffset, content。"
                + "如果 truncated=true，使用 nextOffset 继续读取下一段。"
                + "不依赖 active root，任意节点可用。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String documentId = call.param("documentId", call.param("filename", ""));
        if (documentId.isBlank()) {
            return ToolResult.fail(NAME, "documentId is required. Use import_document_list to find available documents.");
        }

        int offset = parseInt(call.param("offset"), 0);
        int limit = parseInt(call.param("limit"), 8000);
        boolean full = "true".equalsIgnoreCase(call.param("full", "false"));

        try {
            ImportDocumentService.ImportDocumentReadResult result =
                    service.readDocument(documentId, offset, limit, full);

            StringBuilder sb = new StringBuilder();
            sb.append("documentId: ").append(result.documentId()).append("\n");
            sb.append("source: ").append(result.source()).append("\n");
            sb.append("displayName: ").append(result.displayName()).append("\n");
            sb.append("originalLength: ").append(result.originalLength()).append("\n");
            sb.append("offset: ").append(result.offset()).append("\n");
            sb.append("limit: ").append(result.limit()).append("\n");
            sb.append("returnedRange: ").append(result.returnedRange()).append("\n");
            sb.append("truncated: ").append(result.truncated()).append("\n");
            sb.append("nextOffset: ").append(result.nextOffset()).append("\n");
            sb.append("--- content ---\n");
            sb.append(result.content());

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item(result.displayName(), result.documentId(),
                            sb.toString(), 1.0)));

        } catch (ImportDocumentService.ImportDocumentException e) {
            return ToolResult.fail(NAME, "[" + e.errorCode() + "] " + e.getMessage());
        } catch (Exception e) {
            log.error("import_document_read failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "IMPORT_READ_FAILED: " + e.getMessage());
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
