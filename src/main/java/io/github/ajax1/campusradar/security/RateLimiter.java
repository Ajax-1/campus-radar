package io.github.ajax1.campusradar.security;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * 按分钟计数的固定窗口限流。只用于单实例的公开部署，每个新窗口清空计数，内存占用不会随时间增长。
 */
public class RateLimiter {

    private final int limit;
    private final Clock clock;
    private final Map<String, Integer> counts = new HashMap<>();
    private long window = -1;

    public RateLimiter(int limitPerMinute) {
        this(limitPerMinute, Clock.systemUTC());
    }

    RateLimiter(int limitPerMinute, Clock clock) {
        this.limit = limitPerMinute;
        this.clock = clock;
    }

    /**
     * @return 这一分钟还没超限时返回 true，并计入一次
     */
    public synchronized boolean tryAcquire(String key) {
        long now = clock.millis() / 60_000;
        if (now != window) {
            window = now;
            counts.clear();
        }
        return counts.merge(key, 1, Integer::sum) <= limit;
    }
}
