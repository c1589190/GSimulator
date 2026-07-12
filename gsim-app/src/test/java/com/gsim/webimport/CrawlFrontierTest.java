package com.gsim.webimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CrawlFrontier 测试 — 使用 fixture 数据，不访问外网。
 */
@DisplayName("CrawlFrontier")
class CrawlFrontierTest {

    @Test
    @DisplayName("应正确添加和取出 URL")
    void testEnqueueAndDequeue() {
        CrawlFrontier frontier = new CrawlFrontier(50);
        assertTrue(frontier.enqueue("https://example.com/page1", 0));
        assertTrue(frontier.enqueue("https://example.com/page2", 0));

        assertEquals(2, frontier.seenCount());

        var entry = frontier.dequeue();
        assertNotNull(entry);
        assertEquals("https://example.com/page1", entry.url());
        assertEquals(0, entry.depth());

        entry = frontier.dequeue();
        assertNotNull(entry);
        assertEquals("https://example.com/page2", entry.url());
    }

    @Test
    @DisplayName("应拒绝重复 URL")
    void testRejectDuplicates() {
        CrawlFrontier frontier = new CrawlFrontier(50);
        assertTrue(frontier.enqueue("https://example.com/page", 0));
        assertFalse(frontier.enqueue("https://example.com/page", 0));
        assertEquals(1, frontier.seenCount());
    }

    @Test
    @DisplayName("达到 maxPages 限制后应拒绝新 URL")
    void testMaxPagesLimit() {
        CrawlFrontier frontier = new CrawlFrontier(3);
        assertTrue(frontier.enqueue("https://example.com/1", 0));
        assertTrue(frontier.enqueue("https://example.com/2", 0));
        assertTrue(frontier.enqueue("https://example.com/3", 0));
        assertFalse(frontier.enqueue("https://example.com/4", 0));
        assertTrue(frontier.isFull());
        assertEquals(3, frontier.seenCount());
    }

    @Test
    @DisplayName("空队列 dequeue 应返回 null")
    void testDequeueFromEmpty() {
        CrawlFrontier frontier = new CrawlFrontier(50);
        assertNull(frontier.dequeue());
        assertTrue(frontier.isEmpty());
    }

    @Test
    @DisplayName("标记已访问后应正确记录")
    void testMarkVisited() {
        CrawlFrontier frontier = new CrawlFrontier(50);
        frontier.enqueue("https://example.com/page", 0);
        var entry = frontier.dequeue();
        frontier.markVisited(entry.url());
        assertEquals(1, frontier.visitedCount());
    }

    @Test
    @DisplayName("isSeen 应正确判断 URL 是否已加入队列")
    void testIsSeen() {
        CrawlFrontier frontier = new CrawlFrontier(50);
        assertFalse(frontier.isSeen("https://example.com/page"));
        frontier.enqueue("https://example.com/page", 0);
        assertTrue(frontier.isSeen("https://example.com/page"));
    }
}
