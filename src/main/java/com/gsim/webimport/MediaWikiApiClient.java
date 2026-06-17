package com.gsim.webimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaWiki API 客户端 — 使用 MediaWiki API 获取页面内容。
 */
public class MediaWikiApiClient {

    private static final Logger log = LoggerFactory.getLogger(MediaWikiApiClient.class);

    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String userAgent;
    private final String apiUrl;

    public MediaWikiApiClient(String apiUrl, int timeoutSeconds, String userAgent) {
        this.apiUrl = apiUrl;
        this.userAgent = userAgent;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(true)
                .build();
    }

    /**
     * 通过 pageid 获取页面的 parsed HTML。
     */
    public ApiPageResult getPageByPageId(long pageId) throws IOException {
        String url = apiUrl + "?action=parse&pageid=" + pageId +
                "&prop=text|categories|links&format=json";
        return executeParse(url);
    }

    /**
     * 通过 title 获取页面的 parsed HTML。
     */
    public ApiPageResult getPageByTitle(String title) throws IOException {
        String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8);
        String url = apiUrl + "?action=parse&page=" + encodedTitle +
                "&prop=text|categories|links&format=json";
        return executeParse(url);
    }

    /**
     * 通过 curid 获取页面的 parsed HTML。
     */
    public ApiPageResult getPageByCurid(long curid) throws IOException {
        String url = apiUrl + "?action=parse&curid=" + curid +
                "&prop=text|categories|links&format=json";
        return executeParse(url);
    }

    /**
     * 获取指定命名空间的所有页面列表。
     */
    public List<String> getAllPages(int namespace, int limit) throws IOException {
        String url = apiUrl + "?action=query&list=allpages" +
                "&apnamespace=" + namespace +
                "&aplimit=" + Math.min(limit, 500) +
                "&format=json";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            JsonNode root = mapper.readTree(response.body().string());
            List<String> pages = new ArrayList<>();
            JsonNode allpages = root.at("/query/allpages");
            if (allpages.isArray()) {
                for (JsonNode page : allpages) {
                    if (page.has("title")) {
                        pages.add(page.get("title").asText());
                    }
                }
            }
            return pages;
        }
    }

    private ApiPageResult executeParse(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            String body = response.body().string();
            JsonNode root = mapper.readTree(body);

            // 检查错误
            if (root.has("error")) {
                String errorMsg = root.at("/error/info").asText("Unknown error");
                throw new IOException("MediaWiki API error: " + errorMsg);
            }

            JsonNode parse = root.get("parse");
            if (parse == null) {
                throw new IOException("No 'parse' section in response");
            }

            String title = parse.has("title") ? parse.get("title").asText() : "";
            long pageId = parse.has("pageid") ? parse.get("pageid").asLong() : -1;
            String html = parse.has("text") ? parse.at("/text/*").asText("") : "";

            // 提取链接
            List<String> links = new ArrayList<>();
            JsonNode linksNode = parse.get("links");
            if (linksNode != null && linksNode.isArray()) {
                for (JsonNode link : linksNode) {
                    if (link.has("title")) {
                        links.add(link.get("title").asText());
                    }
                }
            }

            return new ApiPageResult(title, pageId, html, links);
        }
    }

    /**
     * API 页面结果。
     */
    public record ApiPageResult(
            String title,
            long pageId,
            String html,
            List<String> links
    ) {}
}
