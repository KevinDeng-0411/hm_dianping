package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {

                try {
                    //获取队列中的订单信息
                    VoucherOrder order = orderTasks.take();
                    //创建订单
                    handlerOrder(order);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void handlerOrder(VoucherOrder order) {
        Long userId = order.getUserId();
        //创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //尝试获取锁
        boolean tryLock = lock.tryLock();
        if (!tryLock) {
            //获取锁失败 返回错误信息
            log.error("不允许重复下单 ");
            return;
        }
        //成功 执行下面的逻辑创建订单
        try {
            proxy.createVoucherOrder(order);
        } finally {
            lock.unlock();
        }
    }


    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Long executed = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        //判断结果是是否为0
        int i = executed.intValue();
        if (i != 0) {
            //不等于0 返回错误信息
            return Result.fail(i == 1 ? "库存不足" : "不能重复下单");
        }

        //为0 订单 用户相关信息存入阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        //返回订单id
        //获取代理对象
        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //根据优惠券id获取优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已结束");
//        }
//        //秒杀不在当前时间 返回异常信息
//
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        //在秒杀时间内 检查库存
//        if (voucher.getStock() < 1) {
//            //库存不足 返回异常信息
//            return Result.fail("库存不足");
//        }
//        //库存充足 扣减库存 创建订单信息 保存订单到数据库
//
//        //实现一人一单
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
//       // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //尝试获取锁
//        boolean tryLock = lock.tryLock();
//        if (!tryLock) {
//            //获取锁失败 返回错误信息
//            return Result.fail("秒杀限定一人一单");
//        }
//        //成功 执行下面的逻辑创建订单
//        try {
//            //获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//
//}

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //根据用户ID和优惠券ID获取订单
        Long userId = voucherOrder.getUserId();

        Long voucherId = voucherOrder.getVoucherId();

        //判断订单是否存在 存在 则返回错误信息
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已经购买过");
            return;
        }
        //不存在 进行库存扣减创建订单
        boolean isUpdate = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!isUpdate) {
            //扣减失败 - 有可能并发扣减为负数？？
            log.error("库存不足");
            return;
        }

        //保存订单到数据库
        save(voucherOrder);


    }

}
