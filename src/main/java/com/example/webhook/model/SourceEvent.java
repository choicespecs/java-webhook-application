package com.example.webhook.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class SourceEvent {
    private String id;
    private String eventType;
    private String eventId;          // ID sent in the outgoing webhook
    private String status;           // PENDING | DELIVERED | CONFIRMED | FAILED
    private LocalDateTime createdAt;
    private LocalDateTime confirmedAt;
    private Map<String, Object> data;
    private String callbackMessage;
}
