package com.gsim.webimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 速率限制器 — 控制请求间隔，防止高频请求。
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final long delayMillis;
    private long lastRequestTime = 0;

    public RateLimiter(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    /**
     * 等待直到可以发送下一个请求。
     */
    public void acquire() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;

        if (elapsed < delayMillis) {
            long waitTime = delayMillis - elapsed;
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Rate limiter sleep interrupted");
            }
        }

        lastRequestTime = System.currentTimeMillis();
    }

    /**
     * 获取延迟毫秒数。
     */
    public long delayMillis() {
        return delayMillis;
    }
}
