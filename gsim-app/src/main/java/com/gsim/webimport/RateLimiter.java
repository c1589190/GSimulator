package com.gsim.webimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 速率限制器 — 控制请求间隔，防止高频请求。
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final long delayMillis;
    private final Object lock = new Object();
    private long lastRequestTime = 0;

    public RateLimiter(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    /**
     * 等待直到可以发送下一个请求。
     * 线程安全：用 synchronized 保证多线程下速率限制不会被绕过。
     */
    public void acquire() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestTime;

            if (elapsed < delayMillis) {
                long waitTime = delayMillis - elapsed;
                // 在同步块外等待，避免长时间持锁
                try {
                    lock.wait(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Rate limiter sleep interrupted");
                }
            }

            lastRequestTime = System.currentTimeMillis();
        }
    }

    /**
     * 获取延迟毫秒数。
     */
    public long delayMillis() {
        return delayMillis;
    }
}
