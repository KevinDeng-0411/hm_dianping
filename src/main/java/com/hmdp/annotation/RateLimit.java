package com.hmdp.annotation;

import java.lang.annotation.*;

/**
 * 方法级限流注解（配合 {@code RateLimitAspect} 使用）。
 * <p>限流 key 由切面自动拼接，规则：{@code rate_limit:{类名}:{方法名}:{维度值}}。
 * 前缀 + 类名 + 方法名属于横切约定，统一在 {@code RateLimitAspect.buildKey} 维护，
 * 这里只需声明：允许次数 / 时间窗口 / 限流维度。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 时间窗口内允许的请求次数 */
    int permits() default 10;

    /** 时间窗口（秒） */
    int windowSeconds() default 60;

    /** 限流维度 */
    KeyType keyType() default KeyType.USER;

    enum KeyType {
        /** 全局 */
        GLOBAL,
        /** 按IP */
        IP,
        /** 按用户ID */
        USER
    }
}
