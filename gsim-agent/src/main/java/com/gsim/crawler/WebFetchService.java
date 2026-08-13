package com.gsim.crawler;

import java.io.IOException;
import java.net.Proxy;
import java.time.Duration;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网页抓取服务 — 使用 OkHttp 获取网页内容。
 */
public class WebFetchService {

    private static final Logger log = LoggerFactory.getLogger(WebFetchService.class);

    private final OkHttpClient client;
    private final String userAgent;

    /**
     * 创建网页抓取服务。
     *
     * @param timeoutSeconds 连接和读取超时秒数
     * @param userAgent 请求时使用的 User-Agent 字符串
     */
    public WebFetchService(int timeoutSeconds, String userAgent) {
        this.userAgent = userAgent;
        this.client = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(true)
                .build();
    }

    /**
     * 抓取指定 URL 的 HTML 内容。
     *
     * @param url 目标网页 URL
     * @return 网页的 HTML 字符串内容
     * @throws IOException 如果 HTTP 请求失败或响应为空
     */
    public String fetch(String url) throws IOException {
        Request request =
                new Request.Builder().url(url).header("User-Agent", userAgent).build();

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
