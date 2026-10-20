package com.example.inventory.search;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class KafkaErrorConfigurationTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void configuresUnassignedPartitionForDltAndClassifiesNonRetryableExceptions() {
        KafkaErrorConfiguration config = new KafkaErrorConfiguration();
        String dltTopic = "inventory.public.products.DLT";

        DefaultErrorHandler errorHandler = (DefaultErrorHandler) config.kafkaErrorHandler(kafkaTemplate, dltTopic);
        assertThat(errorHandler).isNotNull();

        // 1. Verify destination resolver uses partition -1 (letting Kafka partitioner assign partition)
        Object failureTracker = ReflectionTestUtils.invokeMethod(errorHandler, "getFailureTracker");
        assertThat(failureTracker).isNotNull();

        DeadLetterPublishingRecoverer recoverer = (DeadLetterPublishingRecoverer) ReflectionTestUtils.invokeMethod(failureTracker, "getRecoverer");
        assertThat(recoverer).isNotNull();

        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver =
                (BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition>) ReflectionTestUtils.getField(recoverer, "destinationResolver");
        assertThat(destinationResolver).isNotNull();

        ConsumerRecord<String, String> recordOnPartition5 = new ConsumerRecord<>("inventory.public.products", 5, 100L, "key", "val");
        TopicPartition destination = destinationResolver.apply(recordOnPartition5, new RuntimeException("test error"));

        assertThat(destination.topic()).isEqualTo(dltTopic);
        assertThat(destination.partition()).isEqualTo(-1);

        // 2. Verify non-retryable exception classification (poison pills bypass retries)
        org.springframework.classify.BinaryExceptionClassifier classifier =
                (org.springframework.classify.BinaryExceptionClassifier) ReflectionTestUtils.invokeMethod(errorHandler, "getClassifier");
        assertThat(classifier).isNotNull();

        // BinaryExceptionClassifier returns false for fatal/non-retryable exceptions
        assertThat(classifier.classify(new IllegalArgumentException("missing field"))).isFalse();
        assertThat(classifier.classify(new JsonParseException(null, "invalid json"))).isFalse();

        // Transient/recoverable exceptions return true (retryable)
        assertThat(classifier.classify(new IOException("network timeout"))).isTrue();
    }
}
