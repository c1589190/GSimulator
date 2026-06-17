package com.gsim.webimport;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * URL 规范化器 — 处理 URL 标准化、去重、域名提取。
 */
public class UrlNormalizer {

    /**
     * 规范化 URL：移除 fragment、标准化路径、小写化 scheme 和 host。
     * @return 规范化后的 URL 字符串，失败返回 null
     */
    public String normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }

        try {
            URI uri = new URI(rawUrl.trim());

            // 必须有 scheme 和 host
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return null;
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }

            // 规范化 scheme 和 host 为小写
            scheme = scheme.toLowerCase();
            host = host.toLowerCase();

            // 移除 www. 前缀
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }

            // 规范化路径：移除末尾斜杠（除了根路径）
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            } else if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            // 保留 query（排序参数以便去重），移除 fragment
            String query = uri.getQuery();
            if (query != null && !query.isEmpty()) {
                query = sortQueryParams(query);
            }

            // 默认端口：移除
            int port = uri.getPort();
            String portStr = "";
            if (port != -1) {
                if ((scheme.equals("http") && port != 80) || (scheme.equals("https") && port != 443)) {
                    portStr = ":" + port;
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(host).append(portStr).append(path);
            if (query != null) {
                sb.append("?").append(query);
            }

            return sb.toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 提取 URL 的 host。
     */
    public String extractHost(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return "";
            host = host.toLowerCase();
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (URISyntaxException e) {
            return "";
        }
    }

    /**
     * 判断两个 URL 是否属于同一域名。
     */
    public boolean isSameHost(String url1, String url2) {
        String host1 = extractHost(url1);
        String host2 = extractHost(url2);
        return !host1.isEmpty() && host1.equals(host2);
    }

    /**
     * 判断 URL 是否属于指定域名。
     */
    public boolean isHostMatch(String url, String host) {
        String urlHost = extractHost(url);
        return !urlHost.isEmpty() && urlHost.equals(host.toLowerCase());
    }

    /**
     * 将相对 URL 解析为绝对 URL。
     */
    public String resolve(String baseUrl, String relativeUrl) {
        if (relativeUrl == null || relativeUrl.isBlank()) {
            return null;
        }

        try {
            URI base = new URI(baseUrl);
            URI resolved = base.resolve(relativeUrl.trim());
            return normalize(resolved.toString());
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 判断是否是应该跳过的 URL（如 javascript:、mailto:、# 锚点等）。
     */
    public boolean shouldSkip(String url) {
        if (url == null || url.isBlank()) return true;
        String lower = url.toLowerCase().trim();
        return lower.startsWith("javascript:")
                || lower.startsWith("mailto:")
                || lower.startsWith("tel:")
                || lower.startsWith("ftp:")
                || lower.startsWith("data:")
                || lower.startsWith("#");
    }

    private String sortQueryParams(String query) {
        if (query == null || query.isEmpty()) return query;
        String[] pairs = query.split("&");
        java.util.Arrays.sort(pairs);
        return String.join("&", pairs);
    }
}
