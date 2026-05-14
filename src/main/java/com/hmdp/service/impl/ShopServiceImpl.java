package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryByID(Long id) {
        //缓存穿透 Shop shop = queryWithPassThrough(id);
        //缓存击穿 基于互斥锁
        Shop shop = queryWithMutex(id);
        //缓存击穿 基于逻辑过期
        //Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        //从redis中查询
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //未命中 直接返回
            return null;
        }

        //命中 需要将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 需要判断缓存 是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期 返回商铺信息
            return shop;
        }
        //已过期 需尝试获取锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //判断是否获取互斥锁
        boolean lock = tryLock(lockKey);
        if (lock) {
            //获取锁成功 DoubleCheck 此时是否有缓存 否则开启独立线程实现缓存重建
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                RedisData cachedData = JSONUtil.toBean(shopJson, RedisData.class);
                return JSONUtil.toBean((JSONObject) cachedData.getData(), Shop.class);
            }

            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //TODO 模拟才是用这个20s 实际上需要更长时间
                try {
                    this.saveShop2Json(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //返回过期的商铺信息 （无论成功与否都需要）
        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
        //从redis中查询
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //当命中空值时 也视为查询失败
        if (shopJson != null) {
            return null;
        }
        //不存在 根据id在数据库中查询
        Shop shop = getById(id);

        if (shop == null) {
            //不存在  返回404 错误 存入空值 防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    public Shop queryWithMutex(Long id) {
        //从redis中查询
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //当命中空值时 也视为查询失败
        if (shopJson != null) {
            return null;
        }
        //不存在 根据id在数据库中查询
        //尝试获取锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                //获取锁失败 休眠并且重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //获取锁成功后double check一下缓存 如果存在那么就不需要重建
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            //double check后发现缓存不存在 重建缓存
            //获取锁成功 查询数据库 重建缓存

            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);
            if (shop == null) {
                //不存在  返回404 错误 存入空值 防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存在 写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(lockKey);
        }
        return shop;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Json(Long id, Long expireSeconds) {
        //从数据库中获取shop
        Shop shop = getById(id);
        //TODO 模拟重建延时
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        String jsonStr = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, jsonStr);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存，失败时发布到Stream补偿重试
        String key = RedisConstants.CACHE_SHOP_KEY + shop.getId();
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.error("删除缓存失败，发布补偿消息: {}", key, e);
            try {
                stringRedisTemplate.opsForStream().add(
                        RedisConstants.CACHE_INVALIDATE_STREAM,
                        Collections.singletonMap("key", key));
            } catch (Exception ex) {
                log.error("发布缓存补偿消息失败，依赖TTL兜底: {}", key, ex);
            }
        }
        return Result.ok();
    }
}
