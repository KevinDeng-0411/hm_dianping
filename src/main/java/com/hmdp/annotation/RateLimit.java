package com.hmdp.annotation;

import java.lang.annotation.*;

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
