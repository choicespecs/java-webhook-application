package com.example.webhook.controller;

import com.example.webhook.service.WebhookHistoryService;
import com.example.webhook.service.WebhookQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final WebhookHistoryService historyService;
    private final WebhookQueue webhookQueue;

    @GetMapping
    public Map<String, Object> fullStats() {
        Map<String, Object> stats = new LinkedHashMap<>(historyService.getStats());
        stats.put("queueSize", webhookQueue.getQueueSize());
        return stats;
    }
}
