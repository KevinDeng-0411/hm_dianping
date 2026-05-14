package com.hmdp.service.impl;

import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class CacheInvalidateService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        EXECUTOR.submit(this::consume);
    }

    private void consume() {
        String streamKey = RedisConstants.CACHE_INVALIDATE_STREAM;
        String group = RedisConstants.CACHE_INVALIDATE_GROUP;
        String consumer = "consumer-1";

        // 创建消费者组（已存在则忽略）
        try {
            stringRedisTemplate.opsForStream().createGroup(streamKey, group);
        } catch (Exception e) {
            // 消费者组已存在，忽略
        }

        while (true) {
            try {
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(group, consumer),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed())
                );

                if (list == null || list.isEmpty()) {
                    continue;
                }

                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> values = record.getValue();
                String key = (String) values.get("key");
                if (key != null) {
                    retryDelete(key);
                }
                // ACK 确认
                stringRedisTemplate.opsForStream().acknowledge(streamKey, group, record.getId());
            } catch (Exception e) {
                log.error("缓存补偿消费异常", e);
            }
        }
    }

    private void retryDelete(String key) {
        int maxRetries = RedisConstants.CACHE_INVALIDATE_MAX_RETRIES;
        for (int i = 0; i < maxRetries; i++) {
            try {
                stringRedisTemplate.delete(key);
                log.info("缓存补偿成功: {}", key);
                return;
            } catch (Exception e) {
                log.warn("缓存补偿重试 {}/{}: {}", i + 1, maxRetries, key);
                if (i < maxRetries - 1) {
                    try {
                        Thread.sleep(1000L * (i + 1));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        log.error("缓存补偿失败（已达最大重试），等待TTL兜底: {}", key);
    }
}
