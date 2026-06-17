package com.gsim.webimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用网站爬虫 — 使用 BFS 策略爬取普通网页。
 */
public class GenericWebsiteCrawler {

    private static final Logger log = LoggerFactory.getLogger(GenericWebsiteCrawler.class);

    private final WebPageFetcher fetcher;
    private final HtmlTextExtractor textExtractor;
    private final UrlNormalizer urlNormalizer;
    private final RateLimiter rateLimiter;
    private final String startHost;

    public GenericWebsiteCrawler(WebPageFetcher fetcher, RateLimiter rateLimiter, String startHost) {
        this.fetcher = fetcher;
        this.textExtractor = new HtmlTextExtractor();
        this.urlNormalizer = new UrlNormalizer();
        this.rateLimiter = rateLimiter;
        this.startHost = startHost;
    }

    /**
     * 爬取网页。
     * @param frontier 爬取队列
     * @param maxDepth 最大深度
     * @param sameHostOnly 是否只爬同域名
     * @return 爬取结果列表
     */
    public CrawlResult crawl(CrawlFrontier frontier, int maxDepth, boolean sameHostOnly) {
        List<CrawledPage> successPages = new ArrayList<>();
        List<CrawledPage> failedPages = new ArrayList<>();

        while (!frontier.isEmpty()) {
            CrawlFrontier.FrontierEntry entry = frontier.dequeue();
            if (entry == null) break;

            String url = entry.url();
            int depth = entry.depth();

            // 深度检查
            if (depth > maxDepth) {
                log.debug("Skipping {} due to depth {} > {}", url, depth, maxDepth);
                continue;
            }

            // 域名检查
            if (sameHostOnly && !urlNormalizer.isHostMatch(url, startHost)) {
                log.debug("Skipping {} due to host mismatch", url);
                continue;
            }

            // 速率限制
            rateLimiter.acquire();

            // 抓取
            CrawledPage page;
            try {
                String html = fetcher.fetch(url);

                if (html == null || html.isBlank()) {
                    page = CrawledPage.builder(url)
                            .host(urlNormalizer.extractHost(url))
                            .depth(depth)
                            .crawlerName(fetcher.name())
                            .fetchedAt(Instant.now())
                            .success(false)
                            .errorMessage("Empty response")
                            .build();
                    failedPages.add(page);
                    frontier.markVisited(url);
                    continue;
                }

                // 提取内容和链接
                String title = textExtractor.extractTitle(html);
                String cleanedText = textExtractor.extractText(html, url);
                List<String> rawLinks = textExtractor.extractInternalLinks(html, url);

                // 规范化链接并加入队列
                List<String> internalLinks = new ArrayList<>();
                for (String link : rawLinks) {
                    String normalized = urlNormalizer.normalize(link);
                    if (normalized == null) continue;
                    if (sameHostOnly && !urlNormalizer.isHostMatch(normalized, startHost)) continue;
                    internalLinks.add(normalized);
                    frontier.enqueue(normalized, depth + 1);
                }

                page = CrawledPage.builder(url)
                        .host(urlNormalizer.extractHost(url))
                        .title(title)
                        .html(html)
                        .cleanedText(cleanedText)
                        .depth(depth)
                        .internalLinks(internalLinks)
                        .crawlerName(fetcher.name())
                        .fetchedAt(Instant.now())
                        .success(true)
                        .build();

                successPages.add(page);
                log.info("Fetched: {} [{}] ({} chars)", title, depth, cleanedText.length());

            } catch (IOException e) {
                log.warn("Failed to fetch {}: {}", url, e.getMessage());
                page = CrawledPage.failed(url, e.getMessage());
                failedPages.add(page);
            }

            frontier.markVisited(url);
        }

        return new CrawlResult(successPages, failedPages);
    }

    /**
     * 爬取结果。
     */
    public record CrawlResult(List<CrawledPage> successPages, List<CrawledPage> failedPages) {}
}
