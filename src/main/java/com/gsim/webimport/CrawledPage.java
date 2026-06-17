package com.gsim.webimport;

import java.time.Instant;
import java.util.List;

/**
 * 已抓取的页面 — 包含原始 HTML 和提取后的文本。
 */
public class CrawledPage {

    private final String url;
    private final String host;
    private final String title;
    private final String html;
    private final String cleanedText;
    private final Instant fetchedAt;
    private final int depth;
    private final List<String> internalLinks;
    private final String crawlerName;
    private final boolean success;
    private final String errorMessage;

    private CrawledPage(Builder builder) {
        this.url = builder.url;
        this.host = builder.host;
        this.title = builder.title;
        this.html = builder.html;
        this.cleanedText = builder.cleanedText;
        this.fetchedAt = builder.fetchedAt;
        this.depth = builder.depth;
        this.internalLinks = List.copyOf(builder.internalLinks);
        this.crawlerName = builder.crawlerName;
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
    }

    public String url() { return url; }
    public String host() { return host; }
    public String title() { return title; }
    public String html() { return html; }
    public String cleanedText() { return cleanedText; }
    public Instant fetchedAt() { return fetchedAt; }
    public int depth() { return depth; }
    public List<String> internalLinks() { return internalLinks; }
    public String crawlerName() { return crawlerName; }
    public boolean success() { return success; }
    public String errorMessage() { return errorMessage; }

    public static Builder builder(String url) {
        return new Builder(url);
    }

    public static CrawledPage failed(String url, String errorMessage) {
        return builder(url)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    public static class Builder {
        private final String url;
        private String host = "";
        private String title = "";
        private String html = "";
        private String cleanedText = "";
        private Instant fetchedAt = Instant.now();
        private int depth = 0;
        private List<String> internalLinks = List.of();
        private String crawlerName = "generic";
        private boolean success = true;
        private String errorMessage = "";

        public Builder(String url) { this.url = url; }

        public Builder host(String v) { this.host = v; return this; }
        public Builder title(String v) { this.title = v; return this; }
        public Builder html(String v) { this.html = v; return this; }
        public Builder cleanedText(String v) { this.cleanedText = v; return this; }
        public Builder fetchedAt(Instant v) { this.fetchedAt = v; return this; }
        public Builder depth(int v) { this.depth = v; return this; }
        public Builder internalLinks(List<String> v) { this.internalLinks = v; return this; }
        public Builder crawlerName(String v) { this.crawlerName = v; return this; }
        public Builder success(boolean v) { this.success = v; return this; }
        public Builder errorMessage(String v) { this.errorMessage = v; return this; }

        public CrawledPage build() {
            return new CrawledPage(this);
        }
    }
}
