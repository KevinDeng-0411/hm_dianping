package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1767225600;
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String KeyPrefix) {

        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long seconds = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = seconds - BEGIN_TIMESTAMP;
        //生成序列号
        //获取当前日期 精确到天数
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + KeyPrefix + ":" + date);
        //拼接并返回 高32位数时间戳 低32位序列号（或运算）
        return timeStamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("seconds: " + second);
    }
}
