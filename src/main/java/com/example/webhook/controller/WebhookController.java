package com.example.webhook.controller;

import com.example.webhook.model.WebhookPayload;
import com.example.webhook.model.WebhookRecord;
import com.example.webhook.model.WebhookResponse;
import com.example.webhook.service.WebhookHistoryService;
import com.example.webhook.service.WebhookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;
    private final WebhookHistoryService historyService;

    @PostMapping("/receive")
    public ResponseEntity<WebhookResponse> receive(
            @Valid @RequestBody WebhookPayload payload,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature) {
        WebhookRecord record = webhookService.receive(payload, signature);
        WebhookResponse response = WebhookResponse.builder()
                .status("QUEUED")
                .message("Webhook received and queued")
                .receivedEventId(record.getEventId())
                .processedAt(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/history")
    public List<WebhookRecord> history(@RequestParam(defaultValue = "100") int limit) {
        return historyService.getHistory(limit);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return historyService.getStats();
    }

    @DeleteMapping("/history")
    public ResponseEntity<Void> clearHistory() {
        historyService.clear();
        return ResponseEntity.noContent().build();
    }
}
