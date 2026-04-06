package com.example.webhook.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class WebhookPayload {
    @NotBlank(message = "eventType is required")
    private String eventType;

    @NotBlank(message = "eventId is required")
    private String eventId;

    @NotNull(message = "timestamp is required")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    private Map<String, Object> data;
}
