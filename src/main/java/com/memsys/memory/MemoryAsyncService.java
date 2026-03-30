package com.memsys.memory;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单核心后台线程池：用于把“记忆相关”的慢操作（LLM 提取、文件 IO、队列更新等）
 * 从主对话链路中剥离出去，确保主任务不被阻塞。
 */
@Slf4j
@Component
public class MemoryAsyncService {

    private final boolean enabled;
    private final ThreadPoolExecutor executor;
    private final long shutdownTimeoutSeconds;

    public MemoryAsyncService(
            @Value("${memory.async.enabled:true}") boolean enabled,
            @Value("${memory.async.queue-capacity:512}") int queueCapacity,
            @Value("${memory.async.shutdown-timeout-seconds:5}") long shutdownTimeoutSeconds
    ) {
        this.enabled = enabled;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;

        if (!enabled) {
            this.executor = null;
            log.info("MemoryAsyncService disabled; memory tasks will run inline.");
            return;
        }

        int capacity = Math.max(1, queueCapacity);
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                new NamedThreadFactory("memory-async-"),
                (r, ex) -> {
                    // 不要 CallerRuns：那会把阻塞传播回主线程；这里记录并抛出拒绝异常。
                    log.warn("Memory async queue full (capacity={}, queued={}). Dropping task.", capacity, ex.getQueue().size());
                    throw new RejectedExecutionException("Memory async queue full");
                }
        );
        this.executor.prestartAllCoreThreads();
        log.info("MemoryAsyncService started (single thread, queue capacity={})", capacity);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 提交一个“尽力而为”的后台任务：如果队列满则丢弃并返回 false。
     */
    public boolean submit(String taskName, Runnable task) {
        if (!enabled) {
            runInline(taskName, task);
            return true;
        }

        Runnable wrapped = () -> {
            Instant start = Instant.now();
            try {
                task.run();
            } catch (Exception e) {
                log.error("Memory async task failed: {}", taskName, e);
            } finally {
                long ms = Duration.between(start, Instant.now()).toMillis();
                if (ms >= 200) {
                    log.info("Memory async task done: {} ({} ms)", taskName, ms);
                } else {
                    log.debug("Memory async task done: {} ({} ms)", taskName, ms);
                }
            }
        };

        try {
            executor.execute(wrapped);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Memory async task rejected (queue likely full): {}", taskName);
            return false;
        }
    }

    private void runInline(String taskName, Runnable task) {
        Instant start = Instant.now();
        try {
            task.run();
        } catch (Exception e) {
            log.error("Inline memory task failed: {}", taskName, e);
        } finally {
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.debug("Inline memory task done: {} ({} ms)", taskName, ms);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (!enabled || executor == null) {
            return;
        }
        log.info("Shutting down MemoryAsyncService...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("MemoryAsyncService did not stop in time; forcing shutdown now.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger idx = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName(prefix + idx.getAndIncrement());
            return t;
        }
    }
}

