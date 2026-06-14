package com.hmdp.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 缓存失效补偿服务
 * 当ShopServiceImpl更新DB后删除缓存失败时，发送消息到Kafka，
 * 由本服务的 @KafkaListener 消费并重试删除。
 * Kafka自动重试（默认10次），无需手写退避逻辑，删不动等TTL兜底。
 */
@Slf4j
@Service
public class CacheInvalidateService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 由 ShopServiceImpl.update() 在删缓存失败时调用，
     * 将删除任务发送到 Kafka Topic，异步补偿重试
     */
    public void sendInvalidateTask(String key) {
        kafkaTemplate.send("cache-invalidate", key);
        log.debug("缓存失效任务已发布到Kafka: {}", key);
    }

    @KafkaListener(topics = "cache-invalidate", groupId = "hmdp-cache")
    public void onInvalidate(String key) {
        log.debug("开始补偿删除缓存: {}", key);
        stringRedisTemplate.delete(key);
        log.info("缓存补偿删除成功: {}", key);
        // 删除失败自动抛异常，Kafka自动重试，最终失败打日志等TTL兜底
    }
}
