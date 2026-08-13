package com.gsim.crawler;

/**
 * 内容提取器接口 — 从网页中提取正文。
 */
public interface ContentExtractor {
    /**
     * 从网页内容中提取正文。
     *
     * @param html 网页 HTML 内容
     * @param url 网页 URL，用于上下文相关的内容提取
     * @return 提取后的纯文本正文
     */
    String extract(String html, String url);
}
