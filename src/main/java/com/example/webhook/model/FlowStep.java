package com.example.webhook.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Broadcast over /topic/flow to drive the demo animation.
 * Each step represents one visible action in the two-app flow.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowStep {
    private String direction;   // "source-to-target" | "target-to-source" | "internal"
    private String step;        // WEBHOOK_SENT | WEBHOOK_RECEIVED | PROCESSING | CALLBACK_SENT | CONFIRMED | FAILED
    private String eventId;
    private String eventType;
    private String detail;
    private LocalDateTime timestamp;
}
