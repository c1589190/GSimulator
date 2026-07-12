package com.gsim.webimport;

import java.io.IOException;

/**
 * 网页获取器接口 — 从 URL 获取 HTML 内容。
 */
public interface WebPageFetcher {

    /**
     * 获取指定 URL 的 HTML 内容。
     * @param url 目标 URL
     * @return HTML 字符串
     * @throws IOException 如果获取失败
     */
    String fetch(String url) throws IOException;

    /**
     * 获取器名称（用于日志和 CrawledPage 标记）。
     */
    String name();
}
