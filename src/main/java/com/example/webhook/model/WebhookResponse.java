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
public class WebhookResponse {
    private String status;
    private String message;
    private String receivedEventId;
    private LocalDateTime processedAt;
}
