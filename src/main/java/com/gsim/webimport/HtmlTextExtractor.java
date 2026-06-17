package com.gsim.webimport;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * HTML 正文提取器 — 从 HTML 中提取标题和正文，移除无关元素，提取内部链接。
 */
public class HtmlTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(HtmlTextExtractor.class);

    /**
     * 从 HTML 中提取标题。
     */
    public String extractTitle(String html) {
        try {
            Document doc = Jsoup.parse(html);
            // 优先用 <title>
            String title = doc.title();
            if (title != null && !title.isBlank()) {
                return title.trim();
            }
            // 其次用 h1
            Element h1 = doc.selectFirst("h1");
            if (h1 != null) {
                return h1.text().trim();
            }
            return "";
        } catch (Exception e) {
            log.warn("Failed to extract title: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 从 HTML 中提取正文。
     * 移除 script/style/nav/footer/header 等无关元素，
     * 保留 body 正文、列表、表格、blockquote、pre。
     */
    public String extractText(String html, String url) {
        try {
            Document doc = Jsoup.parse(html, url);

            // 移除无关元素
            doc.select("script, style, nav, footer, header, aside, " +
                       ".sidebar, .nav, .menu, .advertisement, .ad, .banner, " +
                       ".comment, .comments, .footer, .header, noscript, iframe").remove();

            // 移除隐藏元素
            doc.select("[style*=\"display:none\"], [style*=\"display: none\"]").remove();
            doc.select("[hidden]").remove();

            Element body = doc.body();
            if (body == null) {
                return doc.text().replaceAll("\\s+", " ").trim();
            }

            StringBuilder sb = new StringBuilder();

            // 标题
            String title = doc.title();
            if (title != null && !title.isBlank()) {
                sb.append(title).append("\n\n");
            }

            // 遍历 body 的直接子元素，保留有意义的内容
            for (Element el : body.children()) {
                String tag = el.tagName().toLowerCase();

                switch (tag) {
                    case "h1": case "h2": case "h3": case "h4": case "h5": case "h6":
                        sb.append("\n## ").append(el.text().trim()).append("\n\n");
                        break;

                    case "p":
                        String text = el.text().trim();
                        if (!text.isBlank()) {
                            sb.append(text).append("\n\n");
                        }
                        break;

                    case "ul":
                        extractListItems(el, sb, "- ");
                        sb.append("\n");
                        break;

                    case "ol":
                        extractListItems(el, sb, "1. ");
                        sb.append("\n");
                        break;

                    case "table":
                        extractTable(el, sb);
                        sb.append("\n");
                        break;

                    case "blockquote":
                        sb.append("\n> ").append(el.text().trim()).append("\n\n");
                        break;

                    case "pre":
                        sb.append("\n```\n").append(el.text()).append("\n```\n\n");
                        break;

                    default:
                        // 对于 div/section/article 等，递归提取文本
                        String childText = el.text().trim();
                        if (!childText.isBlank() && childText.length() > 20) {
                            sb.append(childText).append("\n\n");
                        }
                }
            }

            String result = sb.toString().replaceAll("\\n{3,}", "\n\n").trim();

            if (result.isEmpty()) {
                // 回退到全文本
                result = body.text().replaceAll("\\s+", " ").trim();
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to extract text from {}: {}", url, e.getMessage());
            return "";
        }
    }

    /**
     * 提取内部链接（同域名）。
     */
    public List<String> extractInternalLinks(String html, String baseUri) {
        try {
            Document doc = Jsoup.parse(html, baseUri);
            List<String> links = new ArrayList<>();

            Elements anchors = doc.select("a[href]");
            String baseHost = null;
            try {
                baseHost = new java.net.URI(baseUri).getHost();
            } catch (Exception e) {
                // ignore
            }

            for (Element a : anchors) {
                String absUrl = a.absUrl("href");
                if (absUrl == null || absUrl.isBlank()) continue;

                // 只保留 http/https
                if (!absUrl.startsWith("http://") && !absUrl.startsWith("https://")) continue;

                // 去重
                if (links.contains(absUrl)) continue;

                links.add(absUrl);
            }

            return links;
        } catch (Exception e) {
            log.warn("Failed to extract links from {}: {}", baseUri, e.getMessage());
            return List.of();
        }
    }

    private void extractListItems(Element list, StringBuilder sb, String prefix) {
        for (Element li : list.children()) {
            if ("li".equals(li.tagName().toLowerCase())) {
                sb.append(prefix).append(li.text().trim()).append("\n");
            }
        }
    }

    private void extractTable(Element table, StringBuilder sb) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) return;

        sb.append("\n");
        boolean firstRow = true;
        for (Element row : rows) {
            Elements cells = row.select("th, td");
            if (cells.isEmpty()) continue;

            String rowText = cells.stream()
                    .map(c -> c.text().trim())
                    .collect(Collectors.joining(" | "));
            sb.append("| ").append(rowText).append(" |\n");

            if (firstRow) {
                sb.append("|").append("---|".repeat(cells.size())).append("\n");
                firstRow = false;
            }
        }
    }
}
