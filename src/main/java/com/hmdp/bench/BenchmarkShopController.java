package com.hmdp.bench;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Benchmark-only endpoints (registered only under the "bench" profile,
 * completely absent in normal runs).
 *
 * Exposes the same hot shop key through three cache tiers so JMeter can A/B them:
 *   mode=mysql : DB only            (getById, no cache)
 *   mode=redis : Redis L2 + DB      (queryWithMutex, no Caffeine L1)
 *   mode=two   : Caffeine L1 -> Redis L2 -> DB  (current product path)
 *
 * /bench/l1stats reads Caffeine's recordStats() to back the "L1 hit rate" number.
 */
@Slf4j
@RestController
@RequestMapping("/bench")
@Profile("bench")
public class BenchmarkShopController {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private Cache<Long, Shop> shopCache;

    @GetMapping("/shop/{id}")
    public Result bench(@PathVariable("id") Long id,
                        @RequestParam(value = "mode", defaultValue = "two") String mode) {
        switch (mode) {
            case "mysql":
                return okOrFail(shopService.getById(id));
            case "redis":
                return okOrFail(shopService.queryWithMutex(id));
            case "two":
            default:
                Result r = shopService.queryByID(id);
                if (r == null || !Boolean.TRUE.equals(r.getSuccess()) || r.getData() == null) {
                    return Result.fail("shop not found");
                }
                return Result.ok(r.getData());
        }
    }

    private Result okOrFail(Shop shop) {
        return shop == null ? Result.fail("shop not found") : Result.ok(shop);
    }

    @GetMapping("/l1stats")
    public Result l1Stats() {
        CacheStats stats = shopCache.stats();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hitCount", stats.hitCount());
        m.put("missCount", stats.missCount());
        m.put("requestCount", stats.hitCount() + stats.missCount());
        m.put("hitRate", stats.hitRate());
        m.put("evictionCount", stats.evictionCount());
        return Result.ok(m);
    }
}