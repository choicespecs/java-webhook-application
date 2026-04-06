package com.example.webhook.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallbackPayload {
    private String eventId;
    private String webhookStatus;   // SUCCESS | FAILED
    private String message;
    private LocalDateTime processedAt;
}
