package com.example.inventory.admin;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DltAdminServiceTest {

    @Mock
    private ConsumerFactory<String, String> consumerFactory;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private Consumer<String, String> kafkaConsumer;

    private DltAdminService service;

    private final String primaryTopic = "inventory.public.products";
    private final String dltTopic = "inventory.public.products.DLT";

    @BeforeEach
    void setUp() {
        service = new DltAdminService(consumerFactory, kafkaTemplate, primaryTopic, dltTopic);
        lenient().when(consumerFactory.createConsumer(anyString(), anyString())).thenReturn(kafkaConsumer);
    }

    @Test
    void getMessagesReturnsParsedDltRecords() {
        PartitionInfo partitionInfo = new PartitionInfo(dltTopic, 0, null, null, null);
        when(kafkaConsumer.partitionsFor(dltTopic)).thenReturn(List.of(partitionInfo));

        RecordHeaders headers = new RecordHeaders();
        headers.add("kafka_dlt-exception-fqcn", "java.lang.IllegalArgumentException".getBytes(StandardCharsets.UTF_8));
        headers.add("kafka_dlt-exception-message", "Missing required field: title".getBytes(StandardCharsets.UTF_8));
        headers.add("kafka_dlt-original-topic", primaryTopic.getBytes(StandardCharsets.UTF_8));
        headers.add("kafka_dlt-original-partition", "0".getBytes(StandardCharsets.UTF_8));
        headers.add("kafka_dlt-original-offset", "42".getBytes(StandardCharsets.UTF_8));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                dltTopic, 0, 100L, 1000L, org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                0, 0, "prod-1", "{\"op\":\"c\"}", headers, java.util.Optional.empty()
        );

        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(
                new TopicPartition(dltTopic, 0), List.of(record)
        ));
        when(kafkaConsumer.poll(any(Duration.class))).thenReturn(records);

        List<DltMessageResponse> results = service.getMessages(10);

        assertThat(results).hasSize(1);
        DltMessageResponse msg = results.get(0);
        assertThat(msg.key()).isEqualTo("prod-1");
        assertThat(msg.value()).isEqualTo("{\"op\":\"c\"}");
        assertThat(msg.offset()).isEqualTo(100L);
        assertThat(msg.exceptionClass()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(msg.exceptionMessage()).isEqualTo("Missing required field: title");
        assertThat(msg.originalTopic()).isEqualTo(primaryTopic);
        assertThat(msg.originalPartition()).isEqualTo(0);
        assertThat(msg.originalOffset()).isEqualTo(42L);
    }

    @Test
    void replayWithOffsetsReplaysMatchingRecords() {
        PartitionInfo partitionInfo = new PartitionInfo(dltTopic, 0, null, null, null);
        when(kafkaConsumer.partitionsFor(dltTopic)).thenReturn(List.of(partitionInfo));

        ConsumerRecord<String, String> r1 = new ConsumerRecord<>(dltTopic, 0, 10L, "k1", "v1");
        ConsumerRecord<String, String> r2 = new ConsumerRecord<>(dltTopic, 0, 20L, "k2", "v2");

        when(kafkaConsumer.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Map.of(
                new TopicPartition(dltTopic, 0), List.of(r1, r2)
        )));

        ReplayDltResponse response = service.replay(new ReplayDltRequest(List.of(20L), null, false));

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.replayedCount()).isEqualTo(1);
        verify(kafkaTemplate).send(eq(primaryTopic), eq("k2"), eq("v2"));
        verify(kafkaTemplate, never()).send(eq(primaryTopic), eq("k1"), any());
    }

    @Test
    void replayWithCorrectedValueSendsCorrectedPayload() {
        PartitionInfo partitionInfo = new PartitionInfo(dltTopic, 0, null, null, null);
        when(kafkaConsumer.partitionsFor(dltTopic)).thenReturn(List.of(partitionInfo));

        ConsumerRecord<String, String> r1 = new ConsumerRecord<>(dltTopic, 0, 10L, "k1", "bad_payload");

        when(kafkaConsumer.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Map.of(
                new TopicPartition(dltTopic, 0), List.of(r1)
        )));

        ReplayDltResponse response = service.replay(new ReplayDltRequest(List.of(10L), "{\"fixed\":true}", false));

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.replayedCount()).isEqualTo(1);
        verify(kafkaTemplate).send(eq(primaryTopic), eq("k1"), eq("{\"fixed\":true}"));
    }

    @Test
    void replayAllReplaysAllRecords() {
        PartitionInfo partitionInfo = new PartitionInfo(dltTopic, 0, null, null, null);
        when(kafkaConsumer.partitionsFor(dltTopic)).thenReturn(List.of(partitionInfo));

        ConsumerRecord<String, String> r1 = new ConsumerRecord<>(dltTopic, 0, 10L, "k1", "v1");
        ConsumerRecord<String, String> r2 = new ConsumerRecord<>(dltTopic, 0, 20L, "k2", "v2");

        when(kafkaConsumer.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Map.of(
                new TopicPartition(dltTopic, 0), List.of(r1, r2)
        )));

        ReplayDltResponse response = service.replay(new ReplayDltRequest(null, null, true));

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.replayedCount()).isEqualTo(2);
        verify(kafkaTemplate).send(eq(primaryTopic), eq("k1"), eq("v1"));
        verify(kafkaTemplate).send(eq(primaryTopic), eq("k2"), eq("v2"));
    }
}
