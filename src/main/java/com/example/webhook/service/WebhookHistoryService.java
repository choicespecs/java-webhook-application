package com.example.webhook.service;

import com.example.webhook.model.WebhookRecord;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Service
public class WebhookHistoryService {

    private static final int MAX_HISTORY_SIZE = 1000;
    private final Deque<WebhookRecord> history = new ConcurrentLinkedDeque<>();

    public void addRecord(WebhookRecord record) {
        history.addFirst(record);
        while (history.size() > MAX_HISTORY_SIZE) {
            history.pollLast();
        }
    }

    public void updateRecord(WebhookRecord updated) {
        // ConcurrentLinkedDeque does not support replaceAll; iterate and swap via a temporary list
        List<WebhookRecord> snapshot = new ArrayList<>(history);
        history.clear();
        snapshot.stream()
                .map(r -> r.getId().equals(updated.getId()) ? updated : r)
                .forEach(history::addLast);
    }

    public List<WebhookRecord> getHistory(int limit) {
        return history.stream().limit(limit).collect(Collectors.toList());
    }

    public void clear() {
        history.clear();
    }

    public Map<String, Object> getStats() {
        List<WebhookRecord> all = new ArrayList<>(history);
        Map<String, Long> byStatus = all.stream()
                .collect(Collectors.groupingBy(WebhookRecord::getStatus, Collectors.counting()));
        Map<String, Long> byEventType = all.stream()
                .collect(Collectors.groupingBy(WebhookRecord::getEventType, Collectors.counting()));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", all.size());
        stats.put("byStatus", byStatus);
        stats.put("byEventType", byEventType);
        return stats;
    }
}
