package com.example.inventory.admin;

import java.util.List;

public record ReplayDltRequest(
        List<Long> offsets,
        String correctedValue,
        Boolean replayAll
) {
}
