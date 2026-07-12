package com.gsim.webimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaWiki 批量导入器 — 使用 allpages API 批量获取页面列表，
 * 逐个抓取 parsed HTML，提取正文，写入文件。
 *
 * 流程：
 * 1. listAllPages(prefix, limit) 获取页面标题列表（支持 apcontinue 分页）
 * 2. 对每个标题调用 getPageByTitle() 获取 parsed HTML
 * 3. HtmlTextExtractor 提取正文
 * 4. WebImportFileWriter 写入 import/web/{host}/{subdir}/{safe-title}-{hash}.txt
 */
public class MediaWikiBatchImporter {

    private static final Logger log = LoggerFactory.getLogger(MediaWikiBatchImporter.class);

    private final MediaWikiApiClient apiClient;
    private final WebImportFileWriter fileWriter;
    private final HtmlTextExtractor textExtractor;
    private final RateLimiter rateLimiter;
    private final String host;
    private final String baseUrl;

    public MediaWikiBatchImporter(MediaWikiApiClient apiClient,
                                   WebImportFileWriter fileWriter,
                                   HtmlTextExtractor textExtractor,
                                   RateLimiter rateLimiter,
                                   String host,
                                   String baseUrl) {
        this.apiClient = apiClient;
        this.fileWriter = fileWriter;
        this.textExtractor = textExtractor;
        this.rateLimiter = rateLimiter;
        this.host = host;
        this.baseUrl = baseUrl;
    }

    /**
     * 批量导入 allpages 页面。
     *
     * @param prefix    页面前缀过滤（apprefix），空字符串不过滤
     * @param maxPages  最大总页数
     * @param subdir    输出子目录
     * @return 批量导入结果
     */
    public BatchImportResult importAllPages(String prefix, int maxPages, String subdir) {
        List<Path> writtenFiles = new ArrayList<>();
        List<String> failedTitles = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int pagesFetched = 0;
        int filesWritten = 0;

        String continueToken = null;
        int pageBatchSize = Math.min(maxPages, 500);

        while (pagesFetched < maxPages) {
            try {
                MediaWikiApiClient.AllPagesResult allPagesResult;
                if (continueToken == null || continueToken.isEmpty()) {
                    // 首次请求
                    allPagesResult = apiClient.listAllPages(prefix, pageBatchSize, null);
                } else {
                    // 后续分页
                    allPagesResult = apiClient.listAllPages(prefix, pageBatchSize, continueToken);
                }

                List<String> titles = allPagesResult.titles();
                if (titles.isEmpty()) {
                    break;
                }

                // 逐个抓取每个页面
                for (String title : titles) {
                    if (pagesFetched >= maxPages) break;

                    // 跳过应排除的标题
                    if (MediaWikiCrawler.shouldExcludeTitle(title)) {
                        log.debug("Skipping excluded title: {}", title);
                        continue;
                    }

                    rateLimiter.acquire();

                    try {
                        MediaWikiApiClient.ApiPageResult apiResult = apiClient.getPageByTitle(title);
                        String cleanedText = textExtractor.extractText(apiResult.html(), titleToUrl(title));
                        String pageTitle = !apiResult.title().isBlank() ? apiResult.title() : title;

                        CrawledPage page = CrawledPage.builder(titleToUrl(title))
                                .host(host)
                                .title(pageTitle)
                                .html(apiResult.html())
                                .cleanedText(cleanedText)
                                .depth(0)
                                .crawlerName("mediawiki-batch-allpages")
                                .fetchedAt(Instant.now())
                                .success(true)
                                .build();

                        Path path = fileWriter.write(page, subdir);
                        writtenFiles.add(path);
                        filesWritten++;
                        pagesFetched++;

                    } catch (Exception e) {
                        log.warn("Failed to fetch page '{}': {}", title, e.getMessage());
                        failedTitles.add(title);
                        errors.add("Fetch failed for '" + title + "': " + e.getMessage());
                        pagesFetched++; // 仍然计入页数
                    }
                }

                // 检查是否有更多页
                continueToken = allPagesResult.continueToken();
                if (continueToken == null || continueToken.isEmpty()) {
                    break;
                }

            } catch (Exception e) {
                log.error("allpages API call failed: {}", e.getMessage());
                errors.add("allpages API error: " + e.getMessage());
                break;
            }
        }

        return new BatchImportResult(pagesFetched, filesWritten, writtenFiles, failedTitles, errors);
    }

    private String titleToUrl(String title) {
        try {
            String encoded = java.net.URLEncoder.encode(
                    title.replace(" ", "_"),
                    java.nio.charset.StandardCharsets.UTF_8);
            return baseUrl + "/w/" + encoded;
        } catch (Exception e) {
            return baseUrl + "/w/" + title.replace(" ", "_");
        }
    }

    /**
     * 批量导入结果。
     */
    public record BatchImportResult(
            int pagesFetched,
            int filesWritten,
            List<Path> writtenFiles,
            List<String> failedTitles,
            List<String> errors
    ) {
        public String summary() {
            return String.format(
                    "Wiki allpages import: fetched=%d, files=%d, failed=%d",
                    pagesFetched, filesWritten, failedTitles.size());
        }
    }
}
