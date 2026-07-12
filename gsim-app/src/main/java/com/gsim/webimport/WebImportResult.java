package com.gsim.webimport;

import java.nio.file.Path;
import java.util.List;

/**
 * 网页导入结果 — 汇总一次 web import 操作的结果。
 */
public record WebImportResult(
        String url,
        String host,
        int pagesFetched,
        int pagesSkipped,
        int pagesFailed,
        int filesWritten,
        List<Path> writtenFiles,
        List<String> errors,
        boolean fetchOnly,
        String crawlerName
) {
    public String summary() {
        return String.format(
                "Web import from %s: fetched=%d, skipped=%d, failed=%d, files=%d, fetchOnly=%s, crawler=%s",
                url, pagesFetched, pagesSkipped, pagesFailed, filesWritten, fetchOnly, crawlerName);
    }
}
