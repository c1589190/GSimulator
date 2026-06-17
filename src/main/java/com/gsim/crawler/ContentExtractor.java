package com.gsim.crawler;

/**
 * 内容提取器接口 — 从网页中提取正文。
 */
public interface ContentExtractor {
    String extract(String html, String url);
}
