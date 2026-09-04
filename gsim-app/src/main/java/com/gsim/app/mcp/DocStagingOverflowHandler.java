package com.gsim.app.mcp;

import com.gsim.agentsmanager.mcp.ToolResultOverflowHandler;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.staging.DocStaging;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 溢出暂存 handler — 将超限的 {@link ToolResult.Item#snippet()} 暂存为
 * {@code docs/tmp/} 下的 TMP 文档（经 {@link DocStaging}），并在原位置替换为
 * 含 docId 的提示文本（调用方可用 {@code gsim_doc_read} 读取全文）。
 *
 * <p>行为契约（与 {@link ToolRegistryMcpAdapter} 的回退路径配合）：
 * <ul>
 *   <li>snippet 长度 &gt; {@code stagingThreshold} 的条目被暂存并改写；其余条目原样保留</li>
 *   <li>{@code docStore} 为 null 或 {@code stagingThreshold <= 0} 时返回原结果
 *       （适配器回退到内置截断）</li>
 *   <li>暂存抛 {@link IOException}（写盘失败等）时返回原结果，保证 MCP 响应不中断</li>
 * </ul>
 *
 * <p>实现位于 gsim-app（而非 agentlib）：暂存逻辑依赖 gsim-core DocStore /
 * gsim-agent DocStaging，agentlib 保持零业务依赖。
 */
public final class DocStagingOverflowHandler implements ToolResultOverflowHandler {

    /** 默认 docId 前缀（同时也是去重范围）。 */
    private static final String DEFAULT_DOC_ID_PREFIX = "mcp_";

    private final DocStore docStore;
    private final int stagingThreshold;
    private final String docIdPrefix;

    /**
     * 构造 handler。
     *
     * @param docStore         文档存储（线程共享；暂存内容经 {@link DocStaging} 去重）
     * @param stagingThreshold 触发暂存的 snippet 最小字符数；&lt;= 0 时禁用暂存
     */
    public DocStagingOverflowHandler(DocStore docStore, int stagingThreshold) {
        this(docStore, stagingThreshold, DEFAULT_DOC_ID_PREFIX);
    }

    /**
     * 构造 handler。
     *
     * @param docStore         文档存储（线程共享；暂存内容经 {@link DocStaging} 去重）
     * @param stagingThreshold 触发暂存的 snippet 最小字符数；&lt;= 0 时禁用暂存
     * @param docIdPrefix      暂存文档的 docId 前缀（null 时使用默认 {@code mcp_}）
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2") // DocStore 按设计跨请求线程共享（内容经 DocStaging 去重）
    public DocStagingOverflowHandler(DocStore docStore, int stagingThreshold, String docIdPrefix) {
        this.docStore = docStore;
        this.stagingThreshold = stagingThreshold;
        this.docIdPrefix = docIdPrefix != null ? docIdPrefix : DEFAULT_DOC_ID_PREFIX;
    }

    @Override
    public ToolResult handle(ToolResult result, String toolName) {
        if (docStore == null || stagingThreshold <= 0) {
            return result;
        }

        List<ToolResult.Item> items = result.items();
        List<ToolResult.Item> rewritten = new ArrayList<>(items.size());
        boolean anyStaged = false;
        try {
            for (ToolResult.Item item : items) {
                String snippet = item.snippet();
                if (snippet != null && snippet.length() > stagingThreshold) {
                    String title = item.title() != null && !item.title().isBlank() ? item.title() : toolName;
                    String docId = DocStaging.stage(docStore, docIdPrefix, title, snippet);
                    snippet = DocStaging.stagedNotice(docId, snippet.length());
                    anyStaged = true;
                }
                rewritten.add(new ToolResult.Item(item.title(), item.path(), snippet, item.score()));
            }
        } catch (IOException e) {
            // 暂存失败（写盘/冲突）→ 返回原结果，由适配器回退到截断路径
            return result;
        }
        if (!anyStaged) {
            return result;
        }
        return new ToolResult(result.success(), result.toolName(), rewritten, result.error());
    }
}
