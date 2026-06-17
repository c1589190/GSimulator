package com.gsim.webimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * WebImportManager — 调度网页采集流程。
 *
 * 流程：
 * 1. 检测目标站点类型（MediaWiki / 普通网页）
 * 2. 选择合适的爬虫
 * 3. 爬取页面
 * 4. 写入 import/web/ 目录
 * 5. 返回结果（由调用方决定是否继续入库）
 */
public class WebImportManager {

    private static final Logger log = LoggerFactory.getLogger(WebImportManager.class);

    private final WebImportFileWriter fileWriter;
    private final HtmlTextExtractor textExtractor;
    private final UrlNormalizer urlNormalizer;

    public WebImportManager(Path importDir) {
        this.fileWriter = new WebImportFileWriter(importDir);
        this.textExtractor = new HtmlTextExtractor();
        this.urlNormalizer = new UrlNormalizer();
    }

    /**
     * 执行网页导入。
     *
     * @param request 导入请求
     * @return 导入结果
     */
    public WebImportResult execute(WebImportRequest request) {
        log.info("Starting web import for: {}", request.url());

        String normalizedUrl = urlNormalizer.normalize(request.url().toString());
        if (normalizedUrl == null) {
            return new WebImportResult(
                    request.url().toString(), "", 0, 0, 0, 0,
                    List.of(), List.of("Invalid URL: " + request.url()), request.fetchOnly(), "none");
        }

        String host = urlNormalizer.extractHost(normalizedUrl);

        try {
            fileWriter.ensureDir();
        } catch (IOException e) {
            return new WebImportResult(
                    normalizedUrl, host, 0, 0, 0, 0,
                    List.of(), List.of("Failed to create web import dir: " + e.getMessage()),
                    request.fetchOnly(), "none");
        }

        // 创建 fetcher
        JsoupWebPageFetcher fetcher = new JsoupWebPageFetcher(
                request.timeoutSeconds(), request.userAgent(), request.maxBytesPerPage());

        // 检测 MediaWiki
        MediaWikiSiteDetector detector = new MediaWikiSiteDetector(
                request.timeoutSeconds(), request.userAgent());
        MediaWikiSiteDetector.DetectionResult detection = detector.detect(normalizedUrl);

        List<CrawledPage> successPages;
        List<CrawledPage> failedPages;
        String crawlerName;

        if (detection.isMediaWiki()) {
            log.info("Detected MediaWiki site: {}", detection.siteName());
            crawlerName = "mediawiki-api";

            MediaWikiApiClient apiClient = new MediaWikiApiClient(
                    detection.apiUrl(), request.timeoutSeconds(), request.userAgent());
            RateLimiter rateLimiter = new RateLimiter(request.delayMillis());
            MediaWikiCrawler crawler = new MediaWikiCrawler(
                    apiClient, rateLimiter, host, normalizedUrl);

            CrawlFrontier frontier = new CrawlFrontier(request.maxPages());
            frontier.enqueue(normalizedUrl, 0);

            MediaWikiCrawler.CrawlResult crawlResult = crawler.crawl(
                    frontier, request.maxDepth(), request.sameHostOnly());
            successPages = crawlResult.successPages();
            failedPages = crawlResult.failedPages();

        } else {
            log.info("Using generic crawler for: {}", host);
            crawlerName = "generic";

            RateLimiter rateLimiter = new RateLimiter(request.delayMillis());
            GenericWebsiteCrawler crawler = new GenericWebsiteCrawler(
                    fetcher, rateLimiter, host);

            CrawlFrontier frontier = new CrawlFrontier(request.maxPages());
            frontier.enqueue(normalizedUrl, 0);

            GenericWebsiteCrawler.CrawlResult crawlResult = crawler.crawl(
                    frontier, request.maxDepth(), request.sameHostOnly());
            successPages = crawlResult.successPages();
            failedPages = crawlResult.failedPages();
        }

        // 写入文件
        List<Path> writtenFiles = new ArrayList<>();
        int filesWritten = 0;
        List<String> errors = new ArrayList<>();

        for (CrawledPage page : successPages) {
            try {
                Path filePath = fileWriter.write(page);
                writtenFiles.add(filePath);
                filesWritten++;
            } catch (IOException e) {
                log.error("Failed to write file for {}: {}", page.url(), e.getMessage());
                errors.add("Write failed for " + page.url() + ": " + e.getMessage());
            }
        }

        // 收集失败信息
        for (CrawledPage page : failedPages) {
            errors.add("Fetch failed for " + page.url() + ": " + page.errorMessage());
        }

        fetcher.close();

        return new WebImportResult(
                normalizedUrl, host,
                successPages.size(), 0, failedPages.size(),
                filesWritten, writtenFiles, errors,
                request.fetchOnly(), crawlerName);
    }
}
