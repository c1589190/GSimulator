package com.gsim.webimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MediaWiki 爬虫 — 使用 MediaWiki API 爬取 wiki 页面。
 * 排除 Special/User/File/Category/Template/Help 等命名空间。
 */
public class MediaWikiCrawler {

    private static final Logger log = LoggerFactory.getLogger(MediaWikiCrawler.class);

    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "Special:", "User:", "File:", "Category:", "Template:", "Help:",
            "Talk:", "User talk:", "Template talk:", "Category talk:",
            "File talk:", "Help talk:", "MediaWiki:", "MediaWiki talk:",
            "Module:", "Module talk:", "Gadget:", "Gadget talk:"
    );

    private static final Pattern EXCLUDED_PATTERN = Pattern.compile(
            ".*(编辑|登录|随机|特殊|帮助|讨论|用户|文件|分类|模板|最近更改|链入页面|相关更改|上传文件|特殊页面|打印版本|固定链接|页面信息).*",
            Pattern.CASE_INSENSITIVE
    );

    private final MediaWikiApiClient apiClient;
    private final HtmlTextExtractor textExtractor;
    private final UrlNormalizer urlNormalizer;
    private final RateLimiter rateLimiter;
    private final String startHost;
    private final String baseUrl;

    public MediaWikiCrawler(MediaWikiApiClient apiClient, RateLimiter rateLimiter,
                            String startHost, String baseUrl) {
        this.apiClient = apiClient;
        this.textExtractor = new HtmlTextExtractor();
        this.urlNormalizer = new UrlNormalizer();
        this.rateLimiter = rateLimiter;
        this.startHost = startHost;
        this.baseUrl = baseUrl;
    }

    /**
     * 爬取 MediaWiki 页面。
     */
    public CrawlResult crawl(CrawlFrontier frontier, int maxDepth, boolean sameHostOnly) {
        List<CrawledPage> successPages = new ArrayList<>();
        List<CrawledPage> failedPages = new ArrayList<>();

        while (!frontier.isEmpty()) {
            CrawlFrontier.FrontierEntry entry = frontier.dequeue();
            if (entry == null) break;

            String url = entry.url();
            int depth = entry.depth();

            if (depth > maxDepth) continue;

            // 解析 URL 中的页面标识符
            PageIdentifier ident = parsePageIdentifier(url);
            if (ident == null || shouldExcludeTitle(ident.titleForExclusionCheck())) {
                log.debug("Skipping excluded page: {}", ident);
                frontier.markVisited(url);
                continue;
            }

            rateLimiter.acquire();

            try {
                CrawledPage page = fetchViaApi(ident, url, depth);

                if (page == null) {
                    page = fetchViaHtmlFallback(url, depth);
                }

                if (page.success()) {
                    successPages.add(page);

                    // 将内部链接加入队列
                    for (String link : page.internalLinks()) {
                        String normalized = urlNormalizer.normalize(link);
                        if (normalized == null) continue;
                        if (sameHostOnly && !urlNormalizer.isHostMatch(normalized, startHost)) continue;
                        frontier.enqueue(normalized, depth + 1);
                    }
                } else {
                    failedPages.add(page);
                }

            } catch (Exception e) {
                log.warn("Failed to crawl {}: {}", url, e.getMessage());
                failedPages.add(CrawledPage.failed(url, e.getMessage()));
            }

            frontier.markVisited(url);
        }

        return new CrawlResult(successPages, failedPages);
    }

    /**
     * 根据页面标识符类型调用对应的 API 方法。
     */
    private CrawledPage fetchViaApi(PageIdentifier ident, String url, int depth) throws IOException {
        MediaWikiApiClient.ApiPageResult apiResult;

        switch (ident.type()) {
            case CURID:
                apiResult = apiClient.getPageByCurid(ident.numericValue());
                break;
            case PAGEID:
                apiResult = apiClient.getPageByPageId(ident.numericValue());
                break;
            case TITLE:
            default:
                apiResult = apiClient.getPageByTitle(ident.stringValue());
                break;
        }

        String cleanedText = textExtractor.extractText(apiResult.html(), url);
        String pageTitle = !apiResult.title().isBlank() ? apiResult.title() : ident.stringValue();

        // 将 API 返回的链接转为完整 URL
        List<String> fullLinks = new ArrayList<>();
        for (String linkTitle : apiResult.links()) {
            if (!shouldExcludeTitle(linkTitle)) {
                fullLinks.add(titleToUrl(linkTitle));
            }
        }

        return CrawledPage.builder(url)
                .host(startHost)
                .title(pageTitle)
                .html(apiResult.html())
                .cleanedText(cleanedText)
                .depth(depth)
                .internalLinks(fullLinks)
                .crawlerName("mediawiki-api")
                .fetchedAt(Instant.now())
                .success(true)
                .build();
    }

    private CrawledPage fetchViaHtmlFallback(String url, int depth) {
        return CrawledPage.failed(url, "MediaWiki API fallback not available in this crawler");
    }

    /**
     * 从 URL 中解析页面标识符（curid / pageid / title）。
     */
    public PageIdentifier parsePageIdentifier(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String path = uri.getPath();

            // 先检查 query 参数（curid / pageid / title）
            String query = uri.getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length != 2) continue;

                    switch (kv[0]) {
                        case "curid":
                            try {
                                long curid = Long.parseLong(kv[1]);
                                return PageIdentifier.forCurid(curid);
                            } catch (NumberFormatException e) {
                                return null;
                            }
                        case "pageid":
                            try {
                                long pageid = Long.parseLong(kv[1]);
                                return PageIdentifier.forPageid(pageid);
                            } catch (NumberFormatException e) {
                                return null;
                            }
                        case "title":
                            String title = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                            if (!title.isBlank()) {
                                return PageIdentifier.forTitle(title);
                            }
                            break;
                    }
                }
            }

            // 然后从路径中提取 title
            if (path != null && (path.startsWith("/wiki/") || path.startsWith("/zh/") || path.startsWith("/en/"))) {
                String[] parts = path.split("/");
                if (parts.length >= 3) {
                    String title = java.net.URLDecoder.decode(parts[parts.length - 1], java.nio.charset.StandardCharsets.UTF_8);
                    return PageIdentifier.forTitle(title);
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 URL 中提取原标题字符串（向后兼容旧的 extractTitleFromUrl）。
     * @deprecated 使用 parsePageIdentifier() 代替
     */
    @Deprecated
    public String extractTitleFromUrl(String url) {
        PageIdentifier ident = parsePageIdentifier(url);
        return ident != null ? ident.stringValue() : null;
    }

    /**
     * 将页面标题转换为完整 URL。
     */
    public String titleToUrl(String title) {
        try {
            String encoded = java.net.URLEncoder.encode(title.replace(" ", "_"), java.nio.charset.StandardCharsets.UTF_8);
            return baseUrl + "/wiki/" + encoded;
        } catch (Exception e) {
            return baseUrl + "/wiki/" + title.replace(" ", "_");
        }
    }

    /**
     * 判断页面标题是否应被排除。
     */
    public static boolean shouldExcludeTitle(String title) {
        if (title == null || title.isBlank()) return true;

        for (String prefix : EXCLUDED_PREFIXES) {
            if (title.startsWith(prefix)) return true;
        }

        if (EXCLUDED_PATTERN.matcher(title).matches()) return true;

        return false;
    }

    /**
     * 判断页面是否应被排除（兼容旧 API）。
     * @deprecated 使用 shouldExcludeTitle() 代替
     */
    @Deprecated
    public static boolean shouldExclude(String title) {
        return shouldExcludeTitle(title);
    }

    /**
     * MediaWiki 页面标识符 — 统一表示 curid/pageid/title。
     */
    public record PageIdentifier(IdType type, long numericValue, String stringValue) {

        public enum IdType { CURID, PAGEID, TITLE }

        public static PageIdentifier forCurid(long curid) {
            return new PageIdentifier(IdType.CURID, curid, String.valueOf(curid));
        }

        public static PageIdentifier forPageid(long pageid) {
            return new PageIdentifier(IdType.PAGEID, pageid, String.valueOf(pageid));
        }

        public static PageIdentifier forTitle(String title) {
            return new PageIdentifier(IdType.TITLE, -1, title);
        }

        /**
         * 用于排除检查的标题字符串。
         * curid/pageid 类型的无法从数字判断是否应排除，返回特殊值表示不排除。
         */
        public String titleForExclusionCheck() {
            return type == IdType.TITLE ? stringValue : ".";
        }
    }

    /**
     * 爬取结果。
     */
    public record CrawlResult(List<CrawledPage> successPages, List<CrawledPage> failedPages) {}
}
