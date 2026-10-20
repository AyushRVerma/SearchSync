package com.example.inventory.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ProductReconciliationService reconciliationService;
    private final DltAdminService dltAdminService;

    public AdminController(ProductReconciliationService reconciliationService,
                           DltAdminService dltAdminService) {
        this.reconciliationService = reconciliationService;
        this.dltAdminService = dltAdminService;
    }

    @PostMapping("/reindex")
    public ResponseEntity<ReindexResponse> reindex(@RequestBody(required = false) @Valid ReindexRequest request) throws IOException {
        ReindexResponse response = reconciliationService.reindex(request != null ? request : new ReindexRequest(null, null, null));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/switch-alias")
    public ResponseEntity<Map<String, Object>> switchAlias(@RequestParam String targetIndex) throws IOException {
        if (targetIndex == null || targetIndex.isBlank()) {
            throw new IllegalArgumentException("targetIndex must not be blank");
        }
        reconciliationService.switchAliasTo(targetIndex.trim());
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "targetIndex", targetIndex.trim()
        ));
    }

    @GetMapping("/dlt")
    public ResponseEntity<List<DltMessageResponse>> getDltMessages(
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        List<DltMessageResponse> messages = dltAdminService.getMessages(limit);
        return ResponseEntity.ok(messages);
    }

    @PostMapping("/dlt/replay")
    public ResponseEntity<ReplayDltResponse> replayDltMessages(
            @RequestBody(required = false) @Valid ReplayDltRequest request) {
        ReplayDltResponse response = dltAdminService.replay(request != null ? request : new ReplayDltRequest(null, null, true));
        return ResponseEntity.ok(response);
    }
}
