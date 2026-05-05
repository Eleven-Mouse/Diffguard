package com.diffguard.adapter.webhook;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 IP 的请求频率限制器，使用 Caffeine 实现固定窗口计数。
 * 每个 IP 地址在指定时间窗口内的请求次数不得超过上限。
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final Cache<String, AtomicInteger> requestCounts;
    private final int maxRequests;
    private final Duration window;

    /**
     * @param maxRequests 时间窗口内允许的最大请求数
     * @param window      滑动时间窗口
     */
    public RateLimiter(int maxRequests, Duration window) {
        this.maxRequests = maxRequests;
        this.window = window;
        this.requestCounts = Caffeine.newBuilder()
                .expireAfterWrite(window)
                .maximumSize(10_000)
                .build();
    }

    /**
     * 判断指定 IP 是否被允许发起请求。
     * 使用 Caffeine 的原子 mappingFunction 保证并发安全。
     *
     * @param ip 客户端 IP 地址
     * @return true 表示允许，false 表示超过频率限制
     */
    public boolean allowRequest(String ip) {
        if (ip == null || ip.isBlank()) {
            ip = "unknown";
        }
        AtomicInteger counter = requestCounts.get(ip, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current > maxRequests) {
            log.warn("IP {} 触发频率限制（{}/{}s 内已请求 {} 次）", ip, maxRequests, window.getSeconds(), current);
            return false;
        }
        return true;
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    public Duration getWindow() {
        return window;
    }
}
