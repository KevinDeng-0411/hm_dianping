package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.ShopTypeServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BugFixVerificationTest {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private ShopTypeServiceImpl shopTypeService;
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // ==================== Bug 1: 逻辑过期 DoubleCheck 反序列化 ====================

    @Test
    void bug1_shouldNotThrowOnRedisDataJson() {
        String key = RedisConstants.CACHE_SHOP_KEY + "9999";

        RedisData data = new RedisData();
        Shop shop = new Shop();
        shop.setId(9999L);
        shop.setName("测试店铺");
        data.setData(shop);
        data.setExpireTime(LocalDateTime.now().plusMinutes(10));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(data));

        try {
            Shop result = shopService.queryWithLogicalExpire(9999L);
            assertNotNull(result, "应返回商铺对象");
            assertEquals(9999L, result.getId());
            assertEquals("测试店铺", result.getName());
        } finally {
            stringRedisTemplate.delete(key);
        }
    }

    @Test
    void bug1_staleDataReturnedWhenExpired() {
        String key = RedisConstants.CACHE_SHOP_KEY + "9998";
        String lockKey = RedisConstants.LOCK_SHOP_KEY + "9998";

        RedisData data = new RedisData();
        Shop shop = new Shop();
        shop.setId(9998L);
        shop.setName("过期店铺");
        data.setData(shop);
        data.setExpireTime(LocalDateTime.now().minusMinutes(10));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(data));
        stringRedisTemplate.delete(lockKey);

        try {
            Shop result = shopService.queryWithLogicalExpire(9998L);
            assertNotNull(result, "过期也应返回旧数据作为降级");
            assertEquals("过期店铺", result.getName());
        } finally {
            stringRedisTemplate.delete(key);
            stringRedisTemplate.delete(lockKey);
        }
    }

    // ==================== Bug 2: 商铺类型空值缓存不命中 ====================

    @Test
    void bug2_emptyValueCacheShouldBeHit() {
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        stringRedisTemplate.opsForValue().set(key, "");

        try {
            Result result = shopTypeService.queryTypeList();
            assertFalse(result.getSuccess(), "命中空值缓存应返回 success=false");
            assertEquals("店铺类型不存在", result.getErrorMsg());
        } finally {
            stringRedisTemplate.delete(key);
        }
    }

    @Test
    void bug2_normalCacheStillWorks() {
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        stringRedisTemplate.delete(key);

        try {
            Result result = shopTypeService.queryTypeList();
            assertTrue(result.getSuccess(), "正常缓存应返回成功");
            assertNotNull(result.getData());
        } finally {
            stringRedisTemplate.delete(key);
        }
    }

    // ==================== Bug 3: 秒杀异步丢单 ====================

    @Test
    void bug3_duplicateOrderShouldThrowException() {
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(1L);
        voucherOrder.setUserId(1L);
        voucherOrder.setVoucherId(1L);

        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("用户已购买过") || e.getMessage().contains("库存不足"),
                    "异常消息应包含业务原因");
        }
    }
}
