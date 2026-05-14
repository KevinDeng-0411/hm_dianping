package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Cache<String, List<ShopType>> shopTypeListCache;

    private static final String TYPE_LIST_KEY = "list";

    @Override
    //TODO 存在很多优化空间！！：已增加Caffeine二级缓存 + 空值缓存防穿透 + 过期时间
    public Result queryTypeList() {
        // L1: Caffeine 本地缓存
        List<ShopType> cached = shopTypeListCache.getIfPresent(TYPE_LIST_KEY);
        if (cached != null) {
            return Result.ok(cached);
        }
        // L2: Redis
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        String typeJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(typeJson)) {
            List<ShopType> shopTypeList = JSONUtil.toList(typeJson, ShopType.class);
            shopTypeListCache.put(TYPE_LIST_KEY, shopTypeList);
            return Result.ok(shopTypeList);
        }
        if (typeJson != null) {
            return Result.fail("店铺类型不存在");
        }

        // L3: DB
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        if (shopTypes == null || shopTypes.isEmpty()) {
            // 不存在 则缓存空值，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺类型不存在");
        }

        String jsonStr = JSONUtil.toJsonStr(shopTypes);
        stringRedisTemplate.opsForValue().set(key, jsonStr, RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        shopTypeListCache.put(TYPE_LIST_KEY, shopTypes);
        return Result.ok(shopTypes);
    }
}
