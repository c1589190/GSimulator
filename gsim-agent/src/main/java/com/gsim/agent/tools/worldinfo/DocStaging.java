package com.gsim.agent.tools.worldinfo;

import com.gsim.core.doc.DocStore;
import com.gsim.core.doc.DocType;
import com.gsim.core.doc.Document;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 大文本暂存 helper — query_* 工具与 MCP 溢出暂存共用。
 *
 * <p>超过阈值的内容不内联存储/返回，而是暂存为 TMP 文档（docs/tmp/{docId}.md），
 * 返回 docId 供调用方通过 {@code gsim_doc_read} 读取全文。
 *
 * <p>内容去重：同 docId 前缀下 content 完全相同的已有 TMP 文档直接复用，
 * 反复暂存相同内容不会产生重复文件；内容不同则新建（docId 带时间戳+随机，保证唯一）。
 */
public final class DocStaging {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private DocStaging() {}

    /**
     * 将超长内容暂存为 TMP 文档并返回 docId。
     *
     * @param docStore    文档存储（必须已 init）
     * @param docIdPrefix docId 前缀（如 {@code wstg_query_} / {@code mcp_}），同时是去重范围
     * @param title       文档标题（信息单元地址，如 {@code n0000:worldview:大事件}）
     * @param content     暂存内容
     * @return docId（去重命中时返回已有文档 ID）
     * @throws IOException 新建文档写盘失败或 docId 冲突重试仍失败时抛出
     */
    public static String stage(DocStore docStore, String docIdPrefix, String title, String content) throws IOException {
        return stage(docStore, docIdPrefix, title, content, false, null);
    }

    /**
     * 暂存 TMP 文档；可选地在暂存前清扫过期 TMP 文档。
     *
     * <p>清扫范围 = 同类型（TMP）中 mtime 早于 {@code now - maxAge} 的全部文档；
     * 清扫失败（IO）不影响本次暂存。去重范围 = {@code docIdPrefix} 前缀。
     *
     * @param docStore       文档存储（必须已 init）
     * @param docIdPrefix    docId 前缀（如 {@code wstg_query_} / {@code mcp_}），同时是去重范围
     * @param title          文档标题（信息单元地址）
     * @param content        暂存内容
     * @param cleanupEnabled 是否在暂存前清扫过期 TMP 文档
     * @param maxAge         清扫保留期（mtime 早于 now-maxAge 的 TMP 文档被删除）；cleanupEnabled 时必须非空
     * @return docId（去重命中时返回已有文档 ID）
     * @throws IOException 新建文档写盘失败或 docId 冲突重试仍失败时抛出
     */
    public static String stage(
            DocStore docStore,
            String docIdPrefix,
            String title,
            String content,
            boolean cleanupEnabled,
            Duration maxAge)
            throws IOException {
        if (cleanupEnabled && maxAge != null) {
            try {
                docStore.deleteByTypeOlderThan(DocType.TMP, Instant.now().minus(maxAge));
            } catch (IOException e) {
                // 清扫失败不影响暂存（可降级为仅去重）
            }
        }
        // 内容去重：同前缀下 content 完全相同的 TMP 文档直接复用
        for (Document doc : docStore.listByTypeAndPrefix(DocType.TMP, docIdPrefix)) {
            if (content.equals(doc.content())) {
                return doc.id();
            }
        }

        String docId = newDocId(docIdPrefix);
        Document doc = docStore.create(docId, DocType.TMP, title, content, List.of());
        if (doc == null) { // docId 冲突（极低概率）→ 重试一次：重新生成时间戳+随机再 create
            String retryId = newDocId(docIdPrefix);
            doc = docStore.create(retryId, DocType.TMP, title, content, List.of());
            if (doc == null) {
                throw new IOException("[STAGING_FAILED] 暂存文档创建失败（docId 冲突）");
            }
            docId = retryId;
        }
        return docId;
    }

    /**
     * 构造 query 侧的暂存提示文本（内嵌在 ToolResult.Item.snippet 中）。
     *
     * @param docId     暂存文档 ID
     * @param charCount 原文长度
     * @return 提示文本，引导调用方用 gsim_doc_read 读取全文
     */
    public static String stagedNotice(String docId, int charCount) {
        return "内容已暂存为文档（" + charCount + " 字符，超过阈值）— docId=" + docId + "，使用 gsim_doc_read(docId=\"" + docId
                + "\") 读取全文";
    }

    /**
     * 暂存并返回提示文本；暂存失败（IO/冲突）时降级为内联返回原文，保证查询可用性。
     *
     * @param docStore    文档存储（必须已 init）
     * @param docIdPrefix docId 前缀（如 {@code wstg_query_}）
     * @param title       文档标题（信息单元地址）
     * @param value       暂存内容
     * @return 暂存提示文本（含 docId）或原文（暂存失败时）
     */
    public static String stageOrInline(DocStore docStore, String docIdPrefix, String title, String value) {
        try {
            String docId = stage(docStore, docIdPrefix, title, value);
            return stagedNotice(docId, value.length());
        } catch (IOException e) {
            return value;
        }
    }

    private static String newDocId(String prefix) {
        return prefix + LocalDateTime.now().format(TS) + "_" + randomHex(8);
    }

    private static String randomHex(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(Integer.toHexString(ThreadLocalRandom.current().nextInt(16)));
        }
        return sb.toString();
    }
}
