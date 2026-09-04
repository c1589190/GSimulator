package com.gsim.core.ref;

import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.doc.Document;
import com.gsim.core.importing.ImportDocumentService;
import com.gsim.core.importing.ImportDocumentService.ImportDocumentReadResult;
import java.util.ArrayList;
import java.util.List;

/**
 * 内嵌引用解析器 — 在任意文本中原位展开 {@code @doc:"..."} / {@code @import:"..."} 内容引用。
 *
 * <p>与 {@link RefResolver} 的区别：RefResolver 处理单条完整引用字符串并抛异常；本类在文本中
 * 扫描所有内嵌引用并替换为文档全文，未解析的引用原样保留并记录到 unresolved（供调用方回传 LLM
 * 修正），不会部分替换、不抛异常。
 *
 * <p>语法：引用必须带引号（{@code @doc:"xxx"}），允许前缀与引号间有空格；无引号形态
 * （{@code @doc:xxx}）视为普通文本，不误伤。
 */
public final class InlineRefResolver {

    private static final String DOC_PREFIX = "@doc:";
    private static final String IMPORT_PREFIX = "@import:";
    private static final String MD_SUFFIX = ".md";

    private final DocStore docStore;
    private final ImportDocumentService importService;

    /**
     * 解析结果。
     *
     * @param text 解析后的文本；未解析的引用原样保留，未出现的引用不做任何替换
     * @param unresolved 未解析的引用原文列表（含引号），按出现顺序
     */
    public record ResolveResult(String text, List<String> unresolved) {}

    /**
     * @param docStore 文档存储（{@code @doc:} 解析用）
     * @param importService 导入文档服务（{@code @import:} 解析用）
     */
    public InlineRefResolver(DocStore docStore, ImportDocumentService importService) {
        this.docStore = docStore;
        this.importService = importService;
    }

    /**
     * 解析文本中的所有内嵌引用。
     *
     * @param text 原始文本，可为 null
     * @return 解析结果；null 或空白文本原样返回且 unresolved 为空
     */
    public ResolveResult resolve(String text) {
        if (text == null || text.isBlank()) {
            return new ResolveResult(text, List.of());
        }
        List<String> unresolved = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (pos < text.length()) {
            int at = text.indexOf('@', pos);
            if (at < 0) {
                out.append(text, pos, text.length());
                break;
            }
            out.append(text, pos, at);
            String prefix = null;
            if (text.startsWith(DOC_PREFIX, at)) {
                prefix = DOC_PREFIX;
            } else if (text.startsWith(IMPORT_PREFIX, at)) {
                prefix = IMPORT_PREFIX;
            }
            if (prefix == null) {
                out.append(text, at, at + 1);
                pos = at + 1;
                continue;
            }
            // 引号必须紧随前缀（允许零个空格）：@doc:"xxx"
            int q1 = at + prefix.length();
            while (q1 < text.length() && text.charAt(q1) == ' ') {
                q1++;
            }
            if (q1 >= text.length() || text.charAt(q1) != '"') {
                // 无引号形态：不识别，按普通文本推进（不报错）
                out.append(text, at, q1);
                pos = q1;
                continue;
            }
            int q2 = text.indexOf('"', q1 + 1);
            if (q2 < 0) {
                out.append(text, at, text.length()); // 引号未闭合 → 从 @ 起原样保留到末尾
                break;
            }
            String inner = text.substring(q1 + 1, q2);
            String resolved = tryResolve(prefix, inner); // null = 未解析
            if (resolved == null) {
                unresolved.add(text.substring(at, q2 + 1)); // 引用原文（含引号）
                out.append(text, at, q2 + 1); // 原样保留（不部分替换）
            } else {
                out.append(resolved);
            }
            pos = q2 + 1;
        }
        return new ResolveResult(out.toString(), unresolved);
    }

    /**
     * 尝试解析单个引用；无法解析（文档不存在 / 导入读取异常 / 路径逃逸等）返回 null。
     */
    private String tryResolve(String prefix, String inner) {
        try {
            if (DOC_PREFIX.equals(prefix)) {
                String docId = inner.toLowerCase().endsWith(MD_SUFFIX) ? inner.substring(0, inner.length() - 3) : inner;
                Document doc = docStore.get(docId);
                return doc == null ? null : doc.content();
            } else { // @import:
                ImportDocumentReadResult r = importService.readDocument(inner, 0, Integer.MAX_VALUE, false);
                return r.content();
            }
        } catch (Exception e) { // ImportDocumentException（不存在/不支持类型）等 → 未解析
            return null;
        }
    }
}
