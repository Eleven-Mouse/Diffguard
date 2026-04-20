package com.diffguard.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一线程池管理器。
 * <p>
 * 提供共享的线程池创建和管理，避免各模块独立创建线程池导致资源浪费。
 * 使用引用计数管理生命周期，所有通过此管理器创建的线程池会在 {@link #close()} 时统一关闭。
 * <p>
 * 注意：创建的线程均为 daemon 线程，JVM 退出时可能被直接终止。
 * 调用方应在应用生命周期内显式调用 {@link #close()} 以确保任务完成和数据一致性。
 */
public class ExecutorManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExecutorManager.class);

    private final List<ExecutorService> managedExecutors = new CopyOnWriteArrayList<>();
    private final AtomicInteger threadCounter = new AtomicInteger(0);

    /**
     * 创建固定大小线程池。
     */
    public ExecutorService createFixedPool(int threads, String name) {
        ExecutorService executor = Executors.newFixedThreadPool(threads, namedThreadFactory(name));
        managedExecutors.add(executor);
        return executor;
    }

    private ThreadFactory namedThreadFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name + "-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 优雅关闭所有管理的线程池。
     */
    @Override
    public void close() {
        for (ExecutorService executor : managedExecutors) {
            executor.shutdown();
        }

        for (ExecutorService executor : managedExecutors) {
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    log.warn("线程池未在 10s 内优雅关闭，已强制终止");
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        managedExecutors.clear();
    }
}
