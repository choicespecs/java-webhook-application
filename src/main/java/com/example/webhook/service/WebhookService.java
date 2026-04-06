package com.example.webhook.service;

import com.example.webhook.model.CallbackPayload;
import com.example.webhook.model.FlowStep;
import com.example.webhook.model.WebhookPayload;
import com.example.webhook.model.WebhookRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    @Value("${webhook.secret}")
    private String secret;

    @Value("${source.app.callback-url}")
    private String callbackUrl;

    private final WebhookQueue webhookQueue;
    private final WebhookHistoryService historyService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public WebhookRecord receive(WebhookPayload payload, String signature) {
        if (signature != null && !validateSignature(payload, signature)) {
            throw new IllegalArgumentException("Invalid webhook signature");
        }

        WebhookRecord record = WebhookRecord.builder()
                .id(UUID.randomUUID().toString())
                .eventType(payload.getEventType())
                .eventId(payload.getEventId())
                .receivedAt(LocalDateTime.now())
                .status("QUEUED")
                .payload(payload)
                .retryCount(0)
                .build();

        historyService.addRecord(record);
        webhookQueue.enqueue(record);
        broadcastUpdate();

        broadcastFlow("source-to-target", "WEBHOOK_RECEIVED",
                payload.getEventId(), payload.getEventType(),
                "Target app received " + payload.getEventType() + " — queued for processing");

        return record;
    }

    public void processRecord(WebhookRecord record) {
        record.setStatus("PROCESSING");
        historyService.updateRecord(record);
        broadcastUpdate();
        broadcastFlow("internal", "PROCESSING",
                record.getEventId(), record.getEventType(),
                "Worker thread processing " + record.getEventType());

        try {
            // Simulate processing work
            Thread.sleep(800);
            log.info("Processing webhook event: {} id: {}", record.getEventType(), record.getEventId());
            record.setStatus("SUCCESS");
            record.setProcessedAt(LocalDateTime.now());
        } catch (Exception e) {
            record.setStatus("FAILED");
            record.setErrorMessage(e.getMessage());
            log.error("Failed to process webhook {}: {}", record.getId(), e.getMessage());
        }

        historyService.updateRecord(record);
        broadcastUpdate();

        // Send callback to source app so it knows the outcome
        sendCallback(record);
    }

    private void sendCallback(WebhookRecord record) {
        if (callbackUrl == null || callbackUrl.isBlank()) return;
        try {
            CallbackPayload callback = CallbackPayload.builder()
                    .eventId(record.getEventId())
                    .webhookStatus(record.getStatus())
                    .message("Processed by target app — status: " + record.getStatus())
                    .processedAt(record.getProcessedAt())
                    .build();

            broadcastFlow("target-to-source", "CALLBACK_SENT",
                    record.getEventId(), record.getEventType(),
                    "Target app posting callback to source → " + callbackUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(callbackUrl, new HttpEntity<>(callback, headers), Void.class);

        } catch (Exception e) {
            log.warn("Could not send callback for event {}: {}", record.getEventId(), e.getMessage());
        }
    }

    public String generateSignature(WebhookPayload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(json.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate signature", e);
        }
    }

    private boolean validateSignature(WebhookPayload payload, String signature) {
        String expected = generateSignature(payload);
        return expected.equals(signature);
    }

    private void broadcastUpdate() {
        messagingTemplate.convertAndSend("/topic/stats", historyService.getStats());
        messagingTemplate.convertAndSend("/topic/history", historyService.getHistory(50));
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
}
