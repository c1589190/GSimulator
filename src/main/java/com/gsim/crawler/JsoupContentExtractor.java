package com.gsim.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jsoup 内容提取器 — 从 HTML 中提取正文。
 */
public class JsoupContentExtractor implements ContentExtractor {

    private static final Logger log = LoggerFactory.getLogger(JsoupContentExtractor.class);

    @Override
    public String extract(String html, String url) {
        try {
            Document doc = Jsoup.parse(html, url);

            // 移除脚本、样式、导航等无关元素
            doc.select("script, style, nav, header, footer, aside, .sidebar, .nav, .advertisement").remove();

            // 尝试获取正文
            String body = doc.body() != null ? doc.body().text() : doc.text();

            // 基本清洗：压缩空白
            return body.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            log.error("Failed to extract content from {}: {}", url, e.getMessage());
            return "";
        }
    }
}
