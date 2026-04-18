package com.diffguard.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 统一线程池管理器。
 * <p>
 * 提供共享的线程池创建和管理，避免各模块独立创建线程池导致资源浪费。
 * 使用引用计数管理生命周期，所有通过此管理器创建的线程池会在 {@link #close()} 时统一关闭。
 */
public class ExecutorManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExecutorManager.class);

    private final List<ExecutorService> managedExecutors = new ArrayList<>();

    /**
     * 创建固定大小线程池。
     */
    public ExecutorService createFixedPool(int threads, String name) {
        ExecutorService executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        managedExecutors.add(executor);
        return executor;
    }

    /**
     * 创建单线程调度执行器。
     */
    public ScheduledExecutorService createScheduledPool(String name) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        managedExecutors.add(executor);
        return executor;
    }

    /**
     * 创建可伸缩线程池（用于 Webhook 等场景）。
     */
    public ExecutorService createBoundedPool(int coreSize, int maxSize,
                                              int queueCapacity, String name) {
        ExecutorService executor = new ThreadPoolExecutor(
                coreSize, maxSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, name);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        managedExecutors.add(executor);
        return executor;
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
