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
        // wiki-allpages 模式走独立路径
        if (request.wikiAllpages()) {
            return executeWikiAllPages(request);
        }

        return executeCrawl(request);
    }
    private WebImportResult executeCrawl(WebImportRequest request) {
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

    /**
     * 执行 Wiki allpages 批量导入。
     * 使用 MediaWiki API 的 list=allpages 获取页面列表，非 HTML 爬虫。
     */
    private WebImportResult executeWikiAllPages(WebImportRequest request) {
        String urlStr = request.url().toString();
        log.info("Starting wiki allpages import for: {}", urlStr);

        String host = urlNormalizer.extractHost(urlStr);
        if (host == null || host.isBlank()) {
            return new WebImportResult(
                    urlStr, "", 0, 0, 0, 0,
                    List.of(), List.of("Unable to extract host from URL: " + urlStr),
                    request.fetchOnly(), "none");
        }

        // 规范化 host: m.prts.wiki → prts.wiki
        String fileHost = normalizeHost(host);
        String apiUrl = resolveApiUrl(host);

        log.info("Wiki allpages: host={}, fileHost={}, apiUrl={}, prefix={}, maxPages={}, subdir={}",
                host, fileHost, apiUrl, request.wikiPrefix(), request.maxPages(), request.outputSubdir());

        try {
            fileWriter.ensureDir();
        } catch (IOException e) {
            return new WebImportResult(
                    urlStr, fileHost, 0, 0, 0, 0,
                    List.of(), List.of("Failed to create web import dir: " + e.getMessage()),
                    request.fetchOnly(), "none");
        }

        String prefix = request.wikiPrefix() != null ? request.wikiPrefix() : "";
        String subdir = request.outputSubdir() != null ? request.outputSubdir() : "";

        MediaWikiApiClient apiClient = new MediaWikiApiClient(
                apiUrl, request.timeoutSeconds(), request.userAgent());
        RateLimiter rateLimiter = new RateLimiter(request.delayMillis());
        UrlNormalizer normalizer = new UrlNormalizer();

        // 构建 baseUrl 用于生成页面链接
        String scheme = request.url().getScheme();
        String baseUrl = scheme + "://" + host;

        MediaWikiBatchImporter batchImporter = new MediaWikiBatchImporter(
                apiClient, fileWriter, textExtractor, rateLimiter, fileHost, baseUrl);

        MediaWikiBatchImporter.BatchImportResult batchResult =
                batchImporter.importAllPages(prefix, request.maxPages(), subdir);

        List<String> errors = new ArrayList<>(batchResult.errors());

        return new WebImportResult(
                urlStr, fileHost,
                batchResult.pagesFetched(), 0, batchResult.failedTitles().size(),
                batchResult.filesWritten(), batchResult.writtenFiles(), errors,
                request.fetchOnly(), "mediawiki-batch-allpages");
    }

    /**
     * 规范化 host 名用于文件路径：m.prts.wiki → prts.wiki
     */
    static String normalizeHost(String host) {
        if (host == null) return "";
        if (host.equals("m.prts.wiki")) return "prts.wiki";
        return host;
    }

    /**
     * 根据 host 解析 MediaWiki API URL。
     * prts.wiki / m.prts.wiki 固定使用 https://prts.wiki/api.php
     */
    static String resolveApiUrl(String host) {
        if (host == null) return "";
        if (host.equals("m.prts.wiki") || host.equals("prts.wiki")) {
            return "https://prts.wiki/api.php";
        }
        return "https://" + host + "/api.php";
    }
}
