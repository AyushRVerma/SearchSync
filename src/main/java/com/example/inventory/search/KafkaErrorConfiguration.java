package com.example.inventory.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaErrorConfiguration {

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                         @Value("${app.kafka.product-dlt}") String dlt) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, exception) -> new TopicPartition(dlt, -1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(500L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5_000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                JsonProcessingException.class
        );
        return errorHandler;
    }
}

