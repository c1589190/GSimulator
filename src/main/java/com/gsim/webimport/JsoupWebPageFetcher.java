package com.gsim.webimport;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * JsoupWebPageFetcher — 使用 OkHttp 获取网页 HTML。
 */
public class JsoupWebPageFetcher implements WebPageFetcher {

    private static final Logger log = LoggerFactory.getLogger(JsoupWebPageFetcher.class);

    private final OkHttpClient client;
    private final String userAgent;
    private final int timeoutSeconds;
    private final long maxBytesPerPage;

    public JsoupWebPageFetcher(int timeoutSeconds, String userAgent, long maxBytesPerPage) {
        this.timeoutSeconds = timeoutSeconds;
        this.userAgent = userAgent;
        this.maxBytesPerPage = maxBytesPerPage;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(true)
                .build();
    }

    @Override
    public String fetch(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build();

        log.debug("Fetching: {}", url);

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            if (response.body() == null) {
                throw new IOException("Empty body for " + url);
            }

            // 检查 Content-Type
            String contentType = response.header("Content-Type", "");
            if (contentType.contains("application/pdf")
                    || contentType.contains("application/zip")
                    || contentType.contains("image/")
                    || contentType.contains("audio/")
                    || contentType.contains("video/")) {
                throw new IOException("Unsupported content type: " + contentType + " for " + url);
            }

            // 检查大小限制
            String contentLengthHeader = response.header("Content-Length");
            if (contentLengthHeader != null) {
                long contentLength = Long.parseLong(contentLengthHeader);
                if (contentLength > maxBytesPerPage) {
                    throw new IOException("Content too large: " + contentLength + " bytes for " + url);
                }
            }

            byte[] bytes = response.body().bytes();
            if (bytes.length > maxBytesPerPage) {
                throw new IOException("Content too large: " + bytes.length + " bytes for " + url);
            }

            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Override
    public String name() {
        return "okhttp-jsoup";
    }

    /**
     * 清理资源。
     */
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
