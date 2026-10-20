package com.example.inventory.admin;

import java.util.Map;

public record DltMessageResponse(
        int partition,
        long offset,
        String key,
        String value,
        long timestamp,
        Map<String, String> headers,
        String exceptionClass,
        String exceptionMessage,
        String originalTopic,
        Integer originalPartition,
        Long originalOffset
) {
}
