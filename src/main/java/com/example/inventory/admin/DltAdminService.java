package com.example.inventory.admin;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class DltAdminService {

    private static final Logger log = LoggerFactory.getLogger(DltAdminService.class);

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String primaryTopic;
    private final String dltTopic;

    public DltAdminService(ConsumerFactory<String, String> consumerFactory,
                           KafkaTemplate<String, String> kafkaTemplate,
                           @Value("${app.kafka.product-topic}") String primaryTopic,
                           @Value("${app.kafka.product-dlt}") String dltTopic) {
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.primaryTopic = primaryTopic;
        this.dltTopic = dltTopic;
    }

    public List<DltMessageResponse> getMessages(int limit) {
        List<DltMessageResponse> results = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlt-inspector-" + UUID.randomUUID(), "")) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(dltTopic);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                return List.of();
            }

            List<TopicPartition> topicPartitions = partitionInfos.stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();

            consumer.assign(topicPartitions);
            consumer.seekToBeginning(topicPartitions);

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
            for (ConsumerRecord<String, String> record : records) {
                if (results.size() >= limit) {
                    break;
                }
                results.add(toDltMessageResponse(record));
            }
        } catch (Exception e) {
            log.error("Failed to inspect DLT messages from topic {}: {}", dltTopic, e.getMessage(), e);
            throw new RuntimeException("Failed to inspect DLT messages: " + e.getMessage(), e);
        }
        return results;
    }

    public ReplayDltResponse replay(ReplayDltRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Replay request must not be null");
        }

        boolean replayAll = Boolean.TRUE.equals(request.replayAll());
        List<Long> targetOffsets = request.offsets() != null ? request.offsets() : List.of();
        String correctedValue = request.correctedValue();

        if (!replayAll && targetOffsets.isEmpty() && (correctedValue == null || correctedValue.isBlank())) {
            throw new IllegalArgumentException("Either replayAll=true, offsets, or correctedValue must be specified");
        }

        int replayedCount = 0;
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlt-replayer-" + UUID.randomUUID(), "")) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(dltTopic);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                return new ReplayDltResponse(0, primaryTopic, "NO_MESSAGES", "No partitions found for DLT topic " + dltTopic);
            }

            List<TopicPartition> topicPartitions = partitionInfos.stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();

            consumer.assign(topicPartitions);
            consumer.seekToBeginning(topicPartitions);

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
            Set<Long> offsetSet = new HashSet<>(targetOffsets);

            for (ConsumerRecord<String, String> record : records) {
                boolean match = replayAll || offsetSet.contains(record.offset());
                if (match) {
                    String valueToSend = (correctedValue != null && !correctedValue.isBlank()) ? correctedValue : record.value();
                    kafkaTemplate.send(primaryTopic, record.key(), valueToSend);
                    replayedCount++;
                }
            }
        } catch (Exception e) {
            log.error("Failed to replay DLT messages to primary topic {}: {}", primaryTopic, e.getMessage(), e);
            throw new RuntimeException("Failed to replay DLT messages: " + e.getMessage(), e);
        }

        return new ReplayDltResponse(
                replayedCount,
                primaryTopic,
                "SUCCESS",
                "Replayed " + replayedCount + " message(s) back to " + primaryTopic
        );
    }

    private DltMessageResponse toDltMessageResponse(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        String exceptionClass = null;
        String exceptionMessage = null;
        String originalTopic = null;
        Integer originalPartition = null;
        Long originalOffset = null;

        for (Header header : record.headers()) {
            String key = header.key();
            String value = new String(header.value(), StandardCharsets.UTF_8);
            headers.put(key, value);

            if (key.endsWith("dlt-exception-fqcn")) {
                exceptionClass = value;
            } else if (key.endsWith("dlt-exception-message")) {
                exceptionMessage = value;
            } else if (key.endsWith("dlt-original-topic")) {
                originalTopic = value;
            } else if (key.endsWith("dlt-original-partition")) {
                try {
                    originalPartition = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {}
            } else if (key.endsWith("dlt-original-offset")) {
                try {
                    originalOffset = Long.parseLong(value);
                } catch (NumberFormatException ignored) {}
            }
        }

        return new DltMessageResponse(
                record.partition(),
                record.offset(),
                record.key(),
                record.value(),
                record.timestamp(),
                headers,
                exceptionClass,
                exceptionMessage,
                originalTopic,
                originalPartition,
                originalOffset
        );
    }
}
