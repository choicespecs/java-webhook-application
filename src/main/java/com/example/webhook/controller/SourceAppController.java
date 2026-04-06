package com.example.webhook.controller;

import com.example.webhook.model.CallbackPayload;
import com.example.webhook.model.SourceEvent;
import com.example.webhook.service.SourceAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/source")
@RequiredArgsConstructor
public class SourceAppController {

    private final SourceAppService sourceAppService;

    /**
     * Trigger a new outbound event from the source app.
     * Body: { "eventType": "order.created" }
     */
    @PostMapping("/trigger")
    public ResponseEntity<SourceEvent> trigger(@RequestBody Map<String, String> body) {
        String eventType = body.getOrDefault("eventType", "").trim();
        if (eventType.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(sourceAppService.triggerEvent(eventType));
    }

    /**
     * Callback endpoint — the target app posts here after processing a webhook.
     * This simulates the source app's callback/webhook-result receiver.
     */
    @PostMapping("/callback")
    public ResponseEntity<Void> callback(@RequestBody CallbackPayload payload) {
        sourceAppService.handleCallback(payload);
        return ResponseEntity.ok().build();
    }

    /** Fetch current source event list (for initial page load). */
    @GetMapping("/events")
    public List<SourceEvent> events() {
        return sourceAppService.getEvents();
    }

    /** Clear the source event history. */
    @DeleteMapping("/events")
    public ResponseEntity<Void> clear() {
        sourceAppService.clear();
        return ResponseEntity.noContent().build();
    }
}
