package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Set;
import java.util.HashSet;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    
    @Test
    public void testSaveShop(){
        //TODO 时间应该设置长一些的过期时间（预热热点数据）
        shopService.saveShop2Json(1L, 10L);
    }

    @Test
    public void testNextId() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(500);
        CountDownLatch latch = new CountDownLatch(300);
        Set<Long> idSet = new HashSet<>();
        
        for (int i = 0; i < 300; i++) {
            executorService.submit(() -> {
                try {
                    long id = redisIdWorker.nextId("order");
                    System.out.println("生成的ID: " + id);
                    synchronized (idSet) {
                        idSet.add(id);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        System.out.println("总共生成的ID数量: " + idSet.size());
        System.out.println("是否有重复ID: " + (idSet.size() != 300));
        executorService.shutdown();
    }

}
