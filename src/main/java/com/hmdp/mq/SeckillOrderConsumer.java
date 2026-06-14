package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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

    @KafkaListener(topics = "seckill-order", groupId = "hmdp-seckill")
    public void onMessage(String message) {
        log.debug("收到秒杀订单消息: {}", message);
        try {
            VoucherOrder voucherOrder = JSONUtil.toBean(message, VoucherOrder.class);
            voucherOrderService.handlerOrder(voucherOrder);
        } catch (Exception e) {
            log.error("处理秒杀订单失败，Kafka将自动重试: {}", message, e);
            // 抛出异常触发Kafka重试（默认10次，间隔递增）
            throw e;
        }
    }
}
