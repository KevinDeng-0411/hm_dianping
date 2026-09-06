package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.sql.SQLIntegrityConstraintViolationException;
import javax.annotation.Resource;

/**
 * Kafka 消费者：消费秒杀订单消息，异步创建订单
 * 替代了原来的 Redis Stream VoucherOrderHandler
 */
@Slf4j
@Component
public class SeckillOrderConsumer {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    // concurrency=3 与主题分区数(3)对齐：单实例也能并行消费 3 个分区，缓解洪峰积压
    @KafkaListener(topics = "seckill-order", groupId = "hmdp-seckill", concurrency = "3")
    public void onMessage(String message) {
        log.debug("收到秒杀订单消息: {}", message);
        try {
            VoucherOrder voucherOrder = JSONUtil.toBean(message, VoucherOrder.class);
            voucherOrderService.handlerOrder(voucherOrder);
        } catch (Exception e) {
            // 唯一/主键冲突（含底层 Duplicate entry）= 重复消费且已成功处理过，
            // 视为幂等成功直接吞掉，避免陷入"插入失败→Kafka重试→再失败"的无谓循环
            if (isDuplicateKey(e)) {
                log.warn("检测到重复订单(主键/唯一键冲突)，忽略本次消费: {}", message);
                return;
            }
            log.error("处理秒杀订单失败，Kafka将自动重试: {}", message, e);
            // 抛出异常触发Kafka重试（默认10次，间隔递增）
            throw e;
        }
    }

    private boolean isDuplicateKey(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof DuplicateKeyException
                    || cur instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
        }
        return false;
    }
}
