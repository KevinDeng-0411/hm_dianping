package com.hmdp.config;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class CaffeineConfig {

    @Bean
    public Cache<Long, Shop> shopCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    @Bean
    public Cache<String, List<ShopType>> shopTypeListCache() {
        return Caffeine.newBuilder()
                .maximumSize(10)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
    }

    /**
     * 逻辑过期异步重建缓存用线程池。
     * 演进：早期用 Executors.newFixedThreadPool(10)（无界队列 + 无饱和策略 + 线程无名字），
     * 改为显式 ThreadPoolExecutor：有界队列防无限积压、核心线程按 IO 密集定小、CallerRuns
     * 满时让调用线程兜底（顺带限制了重建并发）。
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor cacheRebuildExecutor() {
        return new ThreadPoolExecutor(
                4, 8,
                60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                ThreadFactoryBuilder.create().setNamePrefix("cache-rebuild-").build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
