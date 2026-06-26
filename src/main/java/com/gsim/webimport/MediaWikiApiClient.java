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
     * 获取指定命名空间的所有页面列表（带前缀过滤和分页）。
     *
     * @param prefix   apprefix 参数，空字符串表示不过滤
     * @param limit    单次 API 调用返回的最大数量
     * @param apfrom   分页起始标题，null 或空表示从头开始
     * @return AllPagesResult 包含标题列表和继续标记
     */
    public AllPagesResult listAllPages(String prefix, int limit, String apfrom) throws IOException {
        StringBuilder urlBuilder = new StringBuilder(apiUrl)
                .append("?action=query&list=allpages")
                .append("&aplimit=").append(Math.min(limit, 500))
                .append("&format=json");

        if (prefix != null && !prefix.isEmpty()) {
            urlBuilder.append("&apprefix=").append(URLEncoder.encode(prefix, StandardCharsets.UTF_8));
        }
        if (apfrom != null && !apfrom.isEmpty()) {
            urlBuilder.append("&apfrom=").append(URLEncoder.encode(apfrom, StandardCharsets.UTF_8));
        }

        Request request = new Request.Builder()
                .url(urlBuilder.toString())
                .header("User-Agent", userAgent)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            JsonNode root = mapper.readTree(response.body().string());

            List<String> titles = new ArrayList<>();
            JsonNode allpages = root.at("/query/allpages");
            if (allpages.isArray()) {
                for (JsonNode page : allpages) {
                    if (page.has("title")) {
                        titles.add(page.get("title").asText());
                    }
                }
            }

            String continueToken = null;
            JsonNode continueNode = root.get("continue");
            if (continueNode != null && continueNode.has("apcontinue")) {
                continueToken = continueNode.get("apcontinue").asText();
            }

            return new AllPagesResult(titles, continueToken);
        }
    }

    /**
     * 获取指定命名空间的所有页面列表。
     * @deprecated 使用 {@link #listAllPages(String, int, String)} 代替
     */
    @Deprecated
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
     * 搜索文章（使用 list=search API）。
     *
     * @param query 搜索关键词
     * @param limit 最大返回数（1-20）
     */
    public List<SearchResult> search(String query, int limit) throws IOException {
        String url = apiUrl + "?action=query&list=search" +
                "&srsearch=" + URLEncoder.encode(query, StandardCharsets.UTF_8) +
                "&srlimit=" + Math.min(limit, 20) +
                "&format=json";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Search HTTP " + response.code());
            }
            JsonNode root = mapper.readTree(response.body().string());
            JsonNode searchResults = root.at("/query/search");

            List<SearchResult> results = new ArrayList<>();
            if (searchResults.isArray()) {
                for (JsonNode sr : searchResults) {
                    String title = sr.has("title") ? sr.get("title").asText() : "";
                    long pageId = sr.has("pageid") ? sr.get("pageid").asLong() : -1;
                    String snippet = sr.has("snippet") ? stripHtml(sr.get("snippet").asText()) : "";
                    int wordCount = sr.has("wordcount") ? sr.get("wordcount").asInt() : 0;
                    results.add(new SearchResult(title, pageId, snippet, wordCount));
                }
            }
            return results;
        }
    }

    /**
     * 获取页面纯文本摘要（仅引言，prop=extracts&exintro&explaintext）。
     */
    public String getExtract(long pageId) {
        try {
            String url = apiUrl + "?action=query&prop=extracts" +
                    "&exintro&explaintext" +
                    "&pageids=" + pageId +
                    "&format=json";

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                JsonNode root = mapper.readTree(response.body().string());
                JsonNode pages = root.at("/query/pages");
                if (pages != null && pages.size() > 0) {
                    String firstKey = pages.fieldNames().next();
                    JsonNode page = pages.get(firstKey);
                    if (page.has("extract")) {
                        return page.get("extract").asText();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("getExtract failed for pageId={}: {}", pageId, e.getMessage());
        }
        return null;
    }

    /**
     * 按标题获取页面摘要。
     */
    public String getExtractByTitle(String title) {
        try {
            String url = apiUrl + "?action=query&prop=extracts" +
                    "&exintro&explaintext" +
                    "&titles=" + URLEncoder.encode(title, StandardCharsets.UTF_8) +
                    "&format=json";

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                JsonNode root = mapper.readTree(response.body().string());
                JsonNode pages = root.at("/query/pages");
                if (pages != null && pages.size() > 0) {
                    String firstKey = pages.fieldNames().next();
                    JsonNode page = pages.get(firstKey);
                    if (page.has("extract")) {
                        return page.get("extract").asText();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("getExtractByTitle failed for '{}': {}", title, e.getMessage());
        }
        return null;
    }

    /** 去除 HTML 标签和实体。 */
    private static String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .trim();
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

    /**
     * allpages API 结果。
     */
    public record AllPagesResult(
            List<String> titles,
            String continueToken
    ) {}

    /**
     * 搜索结果。
     */
    public record SearchResult(
            String title,
            long pageId,
            String snippet,
            int wordCount
    ) {}
}
