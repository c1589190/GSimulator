package com.gsim.webimport;

/**
 * JS 渲染页面获取器接口 — 本阶段只定义接口，默认实现为 disabled。
 */
public interface RenderedPageFetcher {

    /**
     * 获取渲染后的页面 HTML。
     * @param url 目标 URL
     * @return 包含警告信息的 FetchResult
     */
    FetchResult fetchRendered(String url);

    /**
     * 是否已启用 JS 渲染。
     */
    boolean isEnabled();

    /**
     * Fetcher 名称。
     */
    String name();

    /**
     * 渲染获取结果。
     */
    record FetchResult(String html, String warning, boolean success) {
        public static FetchResult ok(String html) {
            return new FetchResult(html, "", true);
        }

        public static FetchResult warning(String html, String warning) {
            return new FetchResult(html, warning, true);
        }

        public static FetchResult error(String warning) {
            return new FetchResult("", warning, false);
        }
    }
}
