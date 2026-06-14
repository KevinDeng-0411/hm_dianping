package com.hmdp.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 配置：自动创建 Topic
 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic seckillOrderTopic() {
        return TopicBuilder.name("seckill-order")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic cacheInvalidateTopic() {
        return TopicBuilder.name("cache-invalidate")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
