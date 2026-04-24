package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

@SpringBootTest
public class BatchTokenGenerator {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void generate1000Tokens() throws Exception {
        String outputFile = "user_tokens.txt";

        System.out.println("开始从数据库获取用户并生成token...");

        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.last("LIMIT 1000");
        List<User> users = userService.list(queryWrapper);

        System.out.println("已获取 " + users.size() + " 个用户");

        BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));

        int count = 0;
        for (User user : users) {
            String token = UUID.randomUUID().toString();

            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

            String tokenKey = LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

            writer.write(token);
            writer.newLine();

            count++;
            if (count % 100 == 0) {
                System.out.println("已处理: " + count + " 个用户");
            }
        }

        writer.flush();
        writer.close();

        System.out.println("\n完成！共生成 " + count + " 个token");
        System.out.println("文件位置: " + new java.io.File(outputFile).getAbsolutePath());
    }
}
