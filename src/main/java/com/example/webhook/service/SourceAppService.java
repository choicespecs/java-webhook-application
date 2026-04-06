package com.example.webhook.service;

import com.example.webhook.model.CallbackPayload;
import com.example.webhook.model.FlowStep;
import com.example.webhook.model.SourceEvent;
import com.example.webhook.model.WebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Service
@RequiredArgsConstructor
public class SourceAppService {

    private final SimpMessagingTemplate messagingTemplate;
    private final RestTemplate restTemplate;

    @Value("${webhook.receiver.url}")
    private String webhookReceiverUrl;

    private final Deque<SourceEvent> events = new ConcurrentLinkedDeque<>();
    private static final int MAX_EVENTS = 30;

    // -------------------------------------------------------
    // Trigger a new outbound event from the source app
    // -------------------------------------------------------

    public SourceEvent triggerEvent(String eventType) {
        String eventId = "evt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        SourceEvent event = SourceEvent.builder()
                .id(UUID.randomUUID().toString())
                .eventType(eventType)
                .eventId(eventId)
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .data(buildSampleData(eventType))
                .build();

        addEvent(event);
        broadcastFlow("source-to-target", "WEBHOOK_SENDING", eventId, eventType,
                "Source app firing " + eventType + " event");

        sendWebhookToTarget(event);
        return event;
    }

    private void sendWebhookToTarget(SourceEvent event) {
        WebhookPayload payload = new WebhookPayload();
        payload.setEventType(event.getEventType());
        payload.setEventId(event.getEventId());
        payload.setTimestamp(event.getCreatedAt());
        payload.setData(event.getData());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(webhookReceiverUrl, new HttpEntity<>(payload, headers), Object.class);

            updateStatus(event.getEventId(), "DELIVERED", null, null);
            broadcastFlow("source-to-target", "WEBHOOK_SENT", event.getEventId(), event.getEventType(),
                    "POST " + webhookReceiverUrl + " → 202 Accepted");
        } catch (Exception e) {
            log.error("Source app failed to send webhook for {}: {}", event.getEventId(), e.getMessage());
            updateStatus(event.getEventId(), "SEND_FAILED", null, "Could not reach target: " + e.getMessage());
            broadcastFlow("internal", "SEND_FAILED", event.getEventId(), event.getEventType(),
                    "Webhook delivery failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------
    // Receive a callback from the target app after processing
    // -------------------------------------------------------

    public void handleCallback(CallbackPayload callback) {
        boolean success = "SUCCESS".equals(callback.getWebhookStatus());
        String newStatus = success ? "CONFIRMED" : "FAILED";
        updateStatus(callback.getEventId(), newStatus, callback.getProcessedAt(), callback.getMessage());

        broadcastFlow("target-to-source", success ? "CONFIRMED" : "FAILED",
                callback.getEventId(), resolveEventType(callback.getEventId()),
                success
                        ? "Source app confirmed: event processed successfully"
                        : "Source app notified of failure: " + callback.getMessage());
    }

    // -------------------------------------------------------
    // State management
    // -------------------------------------------------------

    private void addEvent(SourceEvent event) {
        events.addFirst(event);
        while (events.size() > MAX_EVENTS) events.pollLast();
        broadcastSourceState();
    }

    private void updateStatus(String eventId, String status, LocalDateTime confirmedAt, String message) {
        List<SourceEvent> snapshot = new ArrayList<>(events);
        events.clear();
        for (SourceEvent e : snapshot) {
            if (e.getEventId().equals(eventId)) {
                e.setStatus(status);
                if (confirmedAt != null) e.setConfirmedAt(confirmedAt);
                if (message != null) e.setCallbackMessage(message);
            }
            events.addLast(e);
        }
        broadcastSourceState();
    }

    private String resolveEventType(String eventId) {
        return events.stream()
                .filter(e -> e.getEventId().equals(eventId))
                .map(SourceEvent::getEventType)
                .findFirst()
                .orElse("unknown");
    }

    public List<SourceEvent> getEvents() {
        return new ArrayList<>(events);
    }

    public void clear() {
        events.clear();
        broadcastSourceState();
    }

    // -------------------------------------------------------
    // WebSocket broadcasts
    // -------------------------------------------------------

    private void broadcastSourceState() {
        messagingTemplate.convertAndSend("/topic/source", getEvents());
    }

    private void broadcastFlow(String direction, String step, String eventId, String eventType, String detail) {
        FlowStep flowStep = FlowStep.builder()
                .direction(direction)
                .step(step)
                .eventId(eventId)
                .eventType(eventType)
                .detail(detail)
                .timestamp(LocalDateTime.now())
                .build();
        messagingTemplate.convertAndSend("/topic/flow", flowStep);
    }

    // -------------------------------------------------------
    // Sample data generators per event type
    // -------------------------------------------------------

    private Map<String, Object> buildSampleData(String eventType) {
        Map<String, Object> data = new LinkedHashMap<>();
        Random rng = new Random();
        switch (eventType) {
            case "order.created" -> {
                data.put("orderId", "ORD-" + (1000 + rng.nextInt(9000)));
                data.put("amount", Math.round((10 + rng.nextDouble() * 490) * 100.0) / 100.0);
                data.put("currency", "USD");
                data.put("customerId", "CUST-" + rng.nextInt(1000));
                data.put("items", rng.nextInt(5) + 1);
            }
            case "user.registered" -> {
                int n = rng.nextInt(10000);
                data.put("userId", "USR-" + n);
                data.put("email", "user" + n + "@example.com");
                String[] plans = {"free", "pro", "enterprise"};
                data.put("plan", plans[rng.nextInt(plans.length)]);
            }
            case "payment.processed" -> {
                data.put("paymentId", "PAY-" + rng.nextInt(100000));
                data.put("amount", Math.round((5 + rng.nextDouble() * 995) * 100.0) / 100.0);
                data.put("currency", "USD");
                String[] methods = {"card", "bank_transfer", "wallet"};
                data.put("method", methods[rng.nextInt(methods.length)]);
                data.put("status", "completed");
            }
            case "subscription.cancelled" -> {
                data.put("subscriptionId", "SUB-" + rng.nextInt(10000));
                String[] reasons = {"user_requested", "payment_failed", "plan_expired"};
                data.put("reason", reasons[rng.nextInt(reasons.length)]);
                data.put("effectiveDate", LocalDateTime.now().plusDays(rng.nextInt(30)).toLocalDate().toString());
            }
            default -> data.put("info", "custom event payload");
        }
        return data;
    }
}
