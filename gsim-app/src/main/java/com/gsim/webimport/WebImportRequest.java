package com.gsim.webimport;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 网页导入请求 — 封装 /import URL 命令的所有参数。
 */
public class WebImportRequest {

    private final URI url;
    private final boolean fetchOnly;
    private final boolean crawlEnabled;
    private final int maxPages;
    private final int maxDepth;
    private final long delayMillis;
    private final int timeoutSeconds;
    private final long maxBytesPerPage;
    private final String userAgent;
    private final boolean sameHostOnly;
    private final boolean wikiAllpages;
    private final String wikiPrefix;
    private final String outputSubdir;

    private WebImportRequest(Builder builder) {
        this.url = Objects.requireNonNull(builder.url, "url must not be null");
        this.fetchOnly = builder.fetchOnly;
        this.crawlEnabled = builder.crawlEnabled;
        this.maxPages = builder.maxPages;
        this.maxDepth = builder.maxDepth;
        this.delayMillis = builder.delayMillis;
        this.timeoutSeconds = builder.timeoutSeconds;
        this.maxBytesPerPage = builder.maxBytesPerPage;
        this.userAgent = builder.userAgent;
        this.sameHostOnly = builder.sameHostOnly;
        this.wikiAllpages = builder.wikiAllpages;
        this.wikiPrefix = builder.wikiPrefix;
        this.outputSubdir = builder.outputSubdir;
    }

    public URI url() { return url; }
    public boolean fetchOnly() { return fetchOnly; }
    public boolean crawlEnabled() { return crawlEnabled; }
    public int maxPages() { return maxPages; }
    public int maxDepth() { return maxDepth; }
    public long delayMillis() { return delayMillis; }
    public int timeoutSeconds() { return timeoutSeconds; }
    public long maxBytesPerPage() { return maxBytesPerPage; }
    public String userAgent() { return userAgent; }
    public boolean sameHostOnly() { return sameHostOnly; }
    public boolean wikiAllpages() { return wikiAllpages; }
    public String wikiPrefix() { return wikiPrefix; }
    public String outputSubdir() { return outputSubdir; }

    public String host() {
        return url.getHost();
    }

    public static Builder builder(URI url) {
        return new Builder(url);
    }

    public static class Builder {
        private final URI url;
        private boolean fetchOnly = false;
        private boolean crawlEnabled = true;
        private int maxPages = 50;
        private int maxDepth = 2;
        private long delayMillis = 1000;
        private int timeoutSeconds = 15;
        private long maxBytesPerPage = 5 * 1024 * 1024; // 5MB
        private String userAgent = "GSimulatorBot/0.1";
        private boolean sameHostOnly = true;
        private boolean wikiAllpages = false;
        private String wikiPrefix = "";
        private String outputSubdir = "";

        public Builder(URI url) {
            this.url = url;
        }

        public Builder fetchOnly(boolean v) { this.fetchOnly = v; return this; }
        public Builder crawlEnabled(boolean v) { this.crawlEnabled = v; return this; }
        public Builder maxPages(int v) { this.maxPages = v; return this; }
        public Builder maxDepth(int v) { this.maxDepth = v; return this; }
        public Builder delayMillis(long v) { this.delayMillis = v; return this; }
        public Builder timeoutSeconds(int v) { this.timeoutSeconds = v; return this; }
        public Builder maxBytesPerPage(long v) { this.maxBytesPerPage = v; return this; }
        public Builder userAgent(String v) { this.userAgent = v; return this; }
        public Builder sameHostOnly(boolean v) { this.sameHostOnly = v; return this; }
        public Builder wikiAllpages(boolean v) { this.wikiAllpages = v; return this; }
        public Builder wikiPrefix(String v) { this.wikiPrefix = v; return this; }
        public Builder outputSubdir(String v) { this.outputSubdir = v; return this; }

        public WebImportRequest build() {
            return new WebImportRequest(this);
        }
    }
}
