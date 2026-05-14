package com.hmdp.aspect;

import com.hmdp.annotation.RateLimit;
import com.hmdp.dto.UserDTO;
import com.hmdp.exception.RateLimitException;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;

@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SCRIPT;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("rate_limit.lua"));
        SCRIPT.setResultType(Long.class);
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = buildKey(joinPoint, rateLimit);
        long now = System.currentTimeMillis();

        Long result = stringRedisTemplate.execute(SCRIPT,
                Collections.singletonList(key),
                String.valueOf(rateLimit.windowSeconds()),
                String.valueOf(rateLimit.permits()),
                String.valueOf(now));

        if (result != null && result == 0) {
            log.warn("限流触发: key={}", key);
            throw new RateLimitException("请求过于频繁，请稍后重试");
        }

        return joinPoint.proceed();
    }

    private String buildKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String method = signature.getMethod().getName();
        String prefix = "rate_limit:" + method + ":";

        switch (rateLimit.keyType()) {
            case USER:
                UserDTO user = UserHolder.getUser();
                return prefix + (user != null ? user.getId() : getIp());
            case IP:
                return prefix + getIp();
            case GLOBAL:
            default:
                return prefix + "global";
        }
    }

    private String getIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return "unknown";
        HttpServletRequest request = attrs.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        return ip != null ? ip.split(",")[0].trim() : request.getRemoteAddr();
    }
}
