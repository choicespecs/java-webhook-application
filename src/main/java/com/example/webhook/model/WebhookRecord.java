package com.example.webhook.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class WebhookRecord {
    private String id;
    private String eventType;
    private String eventId;
    private LocalDateTime receivedAt;
    private LocalDateTime processedAt;
    private String status; // QUEUED, PROCESSING, SUCCESS, FAILED
    private String errorMessage;
    private WebhookPayload payload;
    private int retryCount;
}
