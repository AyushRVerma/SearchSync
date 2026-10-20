package com.example.inventory.admin;

public record ReplayDltResponse(
        int replayedCount,
        String targetTopic,
        String status,
        String message
) {
}
