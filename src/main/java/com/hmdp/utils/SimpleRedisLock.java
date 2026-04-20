package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements Ilock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String LOCK_KEY_PREFIX = "lock:";
    private static final String Thread_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript <Long>UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadId = Thread_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
       stringRedisTemplate.execute(UNLOCK_SCRIPT,
               Collections.singletonList(LOCK_KEY_PREFIX+name),
               Thread_PREFIX + Thread.currentThread().getId()
               );
    }
//    @Override
//    public void unLock() {
//        //获取线程标识
//        String threadId = Thread_PREFIX + Thread.currentThread().getId();
//        //获取锁标识
//        String id = stringRedisTemplate.opsForValue().get(LOCK_KEY_PREFIX + name);
//        if (threadId.equals(id)) {
//            //释放锁
//            stringRedisTemplate.delete(LOCK_KEY_PREFIX + name);
//        }
//    }
}
