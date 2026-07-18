package com.gsim.webimport;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

/**
 * 爬取队列 — 管理待爬 URL 的 FIFO 队列，防止重复爬取。
 */
public class CrawlFrontier {

    private final Queue<FrontierEntry> queue = new LinkedList<>();
    private final Set<String> seen = new HashSet<>();
    private final Set<String> visited = new HashSet<>();
    private final int maxPages;

    public CrawlFrontier(int maxPages) {
        this.maxPages = maxPages;
    }

    /**
     * 添加一个 URL 到队列（如果未见过且未超过限制）。
     *
     * @param url   要添加的 URL
     * @param depth 该 URL 的爬取深度
     * @return 是否成功加入队列
     */
    public boolean enqueue(String url, int depth) {
        if (url == null || url.isBlank()) return false;
        if (seen.size() >= maxPages) return false;
        if (seen.contains(url)) return false;

        seen.add(url);
        queue.add(new FrontierEntry(url, depth));
        return true;
    }

    /**
     * 取出下一个待爬 URL。
     *
     * @return 队列头部的条目，如果队列为空则返回 null
     */
    public FrontierEntry dequeue() {
        return queue.poll();
    }

    /**
     * 标记 URL 为已访问。
     *
     * @param url 要标记为已访问的 URL
     */
    public void markVisited(String url) {
        visited.add(url);
    }

    /**
     * 队列是否为空。
     *
     * @return 队列为空返回 true，否则返回 false
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * 已见过的 URL 数量。
     *
     * @return 已见过的 URL 数量（包括已访问和待访问的）
     */
    public int seenCount() {
        return seen.size();
    }

    /**
     * 已访问的 URL 数量。
     *
     * @return 已成功抓取并标记为已访问的 URL 数量
     */
    public int visitedCount() {
        return visited.size();
    }

    /**
     * 是否已见过该 URL。
     *
     * @param url 要检查的 URL
     * @return 该 URL 已在 seen 集合中返回 true，否则返回 false
     */
    public boolean isSeen(String url) {
        return seen.contains(url);
    }

    /**
     * 是否已达到最大页数限制。
     *
     * @return 已见页面数达到上限返回 true，否则返回 false
     */
    public boolean isFull() {
        return seen.size() >= maxPages;
    }

    /**
     * 待爬取的 URL 条目。
     */
    public record FrontierEntry(String url, int depth) {}
}
