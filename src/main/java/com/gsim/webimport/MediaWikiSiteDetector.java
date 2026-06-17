package com.gsim.webimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * MediaWiki 站点检测器 — 检测目标站点是否为 MediaWiki。
 */
public class MediaWikiSiteDetector {

    private static final Logger log = LoggerFactory.getLogger(MediaWikiSiteDetector.class);

    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String userAgent;

    public MediaWikiSiteDetector(int timeoutSeconds, String userAgent) {
        this.userAgent = userAgent;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(true)
                .build();
    }

    /**
     * 检测指定站点是否为 MediaWiki。
     * 通过请求 /api.php?action=query&meta=siteinfo&format=json 判断。
     */
    public DetectionResult detect(String baseUrl) {
        String apiUrl = buildApiUrl(baseUrl);
        if (apiUrl == null) {
            return DetectionResult.notMediaWiki();
        }

        try {
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", userAgent)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return DetectionResult.notMediaWiki();
                }

                String body = response.body().string();
                JsonNode root = mapper.readTree(body);

                // MediaWiki API 响应应包含 "query" 和 "batchcomplete" 字段
                if (root.has("batchcomplete") || root.has("query")) {
                    JsonNode query = root.get("query");
                    if (query != null && query.has("general")) {
                        JsonNode general = query.get("general");
                        String siteName = general.has("sitename") ? general.get("sitename").asText() : "";
                        String generator = general.has("generator") ? general.get("generator").asText() : "";
                        log.info("Detected MediaWiki site: {} (generator: {})", siteName, generator);
                        return DetectionResult.mediaWiki(apiUrl, siteName, generator);
                    }
                    // 即使没有 general，有 query 也可能是 MediaWiki
                    log.info("Detected MediaWiki site (minimal response)");
                    return DetectionResult.mediaWiki(apiUrl, "", "");
                }

                return DetectionResult.notMediaWiki();
            }
        } catch (Exception e) {
            log.debug("MediaWiki detection failed for {}: {}", baseUrl, e.getMessage());
            return DetectionResult.notMediaWiki();
        }
    }

    private String buildApiUrl(String baseUrl) {
        // 从 baseUrl 提取 scheme+host
        try {
            java.net.URI uri = new java.net.URI(baseUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            String portStr = (port != -1) ? ":" + port : "";
            String path = uri.getPath();
            // 如果路径包含 /api.php，直接用它
            if (path != null && path.contains("api.php")) {
                return scheme + "://" + host + portStr + path;
            }
            return scheme + "://" + host + portStr + "/api.php";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 检测结果。
     */
    public record DetectionResult(
            boolean isMediaWiki,
            String apiUrl,
            String siteName,
            String generator
    ) {
        public static DetectionResult notMediaWiki() {
            return new DetectionResult(false, "", "", "");
        }

        public static DetectionResult mediaWiki(String apiUrl, String siteName, String generator) {
            return new DetectionResult(true, apiUrl, siteName, generator);
        }
    }
}
