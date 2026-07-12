package com.gsim.crawler;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * 网页抓取服务 — 使用 OkHttp 获取网页内容。
 */
public class WebFetchService {

    private static final Logger log = LoggerFactory.getLogger(WebFetchService.class);

    private final OkHttpClient client;
    private final String userAgent;

    public WebFetchService(int timeoutSeconds, String userAgent) {
        this.userAgent = userAgent;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(true)
                .build();
    }

    /**
     * 抓取指定 URL 的 HTML 内容。
     */
    public String fetch(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            if (response.body() == null) {
                throw new IOException("Empty body for " + url);
            }
            return response.body().string();
        }
    }

    /**
     * 清理客户端资源。
     */
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
