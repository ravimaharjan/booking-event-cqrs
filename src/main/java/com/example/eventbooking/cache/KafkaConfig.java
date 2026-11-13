package com.example.eventbooking.cache;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true", matchIfMissing = true)
class KafkaConfig {

    /** Created up front so the consumer can subscribe before the first outbox message exists. */
    @Bean
    NewTopic outboxEventTopic() {
        return TopicBuilder.name(CacheInvalidationConsumer.TOPIC).partitions(3).replicas(1).build();
    }

    /** Retries every second for about 30 seconds (Redis briefly down), then logs and moves on. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, 30));
    }
}
