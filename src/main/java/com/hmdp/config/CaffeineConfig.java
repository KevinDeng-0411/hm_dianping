package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
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
}
