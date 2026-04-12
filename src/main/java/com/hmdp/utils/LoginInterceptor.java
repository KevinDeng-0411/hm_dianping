package com.hmdp.utils;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {
    //只能使用构造函数注入 因为这个类是我们手动创建不是由spring创建的！！ 要看谁用了它 进行注入
    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取请求头的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            response.sendError(401);
            return false;
        }
        //根据token获取redis 中用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> UserMap = stringRedisTemplate.opsForHash().entries(key);

        //判断是否存在
        if (UserMap == null) {
            //不存在 拦截 401状态码
            response.sendError(401);
            return false;
        }
        //将查询到的hash数据转换为UserDto对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(UserMap, new UserDTO(), false);
        //存在 保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
        //刷新token 有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户 防止内存泄漏
        UserHolder.removeUser();
    }


}
