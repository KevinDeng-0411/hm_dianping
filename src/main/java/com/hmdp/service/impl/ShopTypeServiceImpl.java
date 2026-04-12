package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
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
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    //TODO 存在很多优化空间！！：现在已经有查询为空存入空值放穿透 设置过期时间
    public Result queryTypeList() {
        // 从redis中查询店铺类型
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        String typeJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(typeJson)) {
            // 存在 直接返回
            List<ShopType> shopTypeList = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        
        // 不存在 则从数据库中进行查询
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        
        // 再判断从数据库中取出的对象是否存在
        if (shopTypes == null || shopTypes.isEmpty()) {
            // TODO 不存在 则缓存空值，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺类型不存在");
        }
        
        // 存在 则转为json对象存入redis，设置过期时间
        String jsonStr = JSONUtil.toJsonStr(shopTypes);
        stringRedisTemplate.opsForValue().set(key, jsonStr, RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        
        // 返回
        return Result.ok(shopTypes);
    }
}
