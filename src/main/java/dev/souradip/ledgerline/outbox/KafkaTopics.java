package dev.souradip.ledgerline.outbox;

import dev.souradip.ledgerline.LedgerProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
class KafkaTopics {

    @Bean
    NewTopic transfersTopic(LedgerProperties properties) {
        return TopicBuilder.name(properties.outbox().topic()).partitions(6).replicas(1).build();
    }
}
